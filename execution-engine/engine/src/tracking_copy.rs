use std::collections::{BTreeMap, HashMap};

use linked_hash_map::LinkedHashMap;
use parking_lot::Mutex;

use common::key::Key;
use common::value::Value;
use shared::newtypes::{CorrelationId, Validated};
use shared::transform::{self, Transform, TypeMismatch};
use storage::global_state::StateReader;

use engine_state::execution_effect::ExecutionEffect;
use engine_state::op::Op;
use meter::heap_meter::HeapSize;
use meter::Meter;
use utils::add;

#[derive(Debug)]
pub enum QueryResult {
    Success(Value),
    ValueNotFound(String),
}

/// Keeps track of already accessed keys.
/// We deliberately separate cached Reads from cached mutations
/// because we want to invalidate Reads' cache so it doesn't grow too fast.
pub struct TrackingCopyCache<M> {
    max_cache_size: usize,
    current_cache_size: Mutex<usize>,
    reads_cached: LinkedHashMap<Key, Value>,
    muts_cached: HashMap<Key, Value>,
    meter: M,
}

impl<M: Meter<Key, Value>> TrackingCopyCache<M> {
    /// Creates instance of `TrackingCopyCache` with specified `max_cache_size`,
    /// above which least-recently-used elements of the cache are invalidated.
    /// Measurements of elements' "size" is done with the usage of `Meter` instance.
    pub fn new(max_cache_size: usize, meter: M) -> TrackingCopyCache<M> {
        TrackingCopyCache {
            max_cache_size,
            current_cache_size: Mutex::new(0),
            reads_cached: LinkedHashMap::new(),
            muts_cached: HashMap::new(),
            meter,
        }
    }

    /// Inserts `key` and `value` pair to Read cache.
    pub fn insert_read(&mut self, key: Key, value: Value) {
        let element_size = Meter::measure(&self.meter, &key, &value);
        self.reads_cached.insert(key, value);
        *self.current_cache_size.lock() += element_size;
        while *self.current_cache_size.lock() > self.max_cache_size {
            match self.reads_cached.pop_front() {
                Some((k, v)) => {
                    let element_size = Meter::measure(&self.meter, &k, &v);
                    *self.current_cache_size.lock() -= element_size;
                }
                None => break,
            }
        }
    }

    /// Inserts `key` and `value` pair to Write/Add cache.
    pub fn insert_write(&mut self, key: Key, value: Value) {
        self.muts_cached.insert(key, value.clone());
    }

    /// Gets value from `key` in the cache.
    pub fn get(&mut self, key: &Key) -> Option<&Value> {
        if let Some(value) = self.muts_cached.get(&key) {
            return Some(value);
        };

        self.reads_cached.get_refresh(key).map(|v| &*v)
    }

    pub fn is_empty(&self) -> bool {
        self.reads_cached.is_empty() && self.muts_cached.is_empty()
    }
}

pub struct TrackingCopy<R> {
    reader: R,
    cache: TrackingCopyCache<HeapSize>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
}

#[derive(Debug)]
pub enum AddResult {
    Success,
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
}

impl<R: StateReader<Key, Value>> TrackingCopy<R> {
    pub fn new(reader: R) -> TrackingCopy<R> {
        TrackingCopy {
            reader,
            cache: TrackingCopyCache::new(1024 * 16, HeapSize), //TODO: Should `max_cache_size` be fraction of Wasm memory limit?
            ops: HashMap::new(),
            fns: HashMap::new(),
        }
    }

    pub fn get(
        &mut self,
        correlation_id: CorrelationId,
        k: &Key,
    ) -> Result<Option<Value>, R::Error> {
        if let Some(value) = self.cache.get(k) {
            return Ok(Some(value.to_owned()));
        }
        if let Some(value) = self.reader.read(correlation_id, k)? {
            self.cache.insert_read(*k, value.to_owned());
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn read(
        &mut self,
        correlation_id: CorrelationId,
        k: &Validated<Key>,
    ) -> Result<Option<Value>, R::Error> {
        let k = k.normalize();
        if let Some(value) = self.get(correlation_id, &k)? {
            add(&mut self.ops, k, Op::Read);
            add(&mut self.fns, k, Transform::Identity);
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn write(&mut self, k: Validated<Key>, v: Validated<Value>) {
        let v_local = v.into_raw();
        let k = k.normalize();
        self.cache.insert_write(k, v_local.clone());
        add(&mut self.ops, k, Op::Write);
        add(&mut self.fns, k, Transform::Write(v_local));
    }

    /// Ok(None) represents missing key to which we want to "add" some value.
    /// Ok(Some(unit)) represents successful operation.
    /// Err(error) is reserved for unexpected errors when accessing global state.
    pub fn add(
        &mut self,
        correlation_id: CorrelationId,
        k: Validated<Key>,
        v: Validated<Value>,
    ) -> Result<AddResult, R::Error> {
        let k = k.normalize();
        match self.get(correlation_id, &k)? {
            None => Ok(AddResult::KeyNotFound(k)),
            Some(curr) => {
                let t = match v.into_raw() {
                    Value::Int32(i) => Transform::AddInt32(i),
                    Value::UInt128(i) => Transform::AddUInt128(i),
                    Value::UInt256(i) => Transform::AddUInt256(i),
                    Value::UInt512(i) => Transform::AddUInt512(i),
                    Value::NamedKey(n, k) => {
                        let mut map = BTreeMap::new();
                        map.insert(n, k);
                        Transform::AddKeys(map)
                    }
                    other => {
                        return Ok(AddResult::TypeMismatch(TypeMismatch::new(
                            "Int32 or UInt* or NamedKey".to_string(),
                            other.type_string(),
                        )))
                    }
                };
                match t.clone().apply(curr) {
                    Ok(new_value) => {
                        self.cache.insert_write(k, new_value);
                        add(&mut self.ops, k, Op::Add);
                        add(&mut self.fns, k, t);
                        Ok(AddResult::Success)
                    }
                    Err(transform::Error::TypeMismatch(type_mismatch)) => {
                        Ok(AddResult::TypeMismatch(type_mismatch))
                    }
                }
            }
        }
    }

    pub fn effect(&self) -> ExecutionEffect {
        ExecutionEffect::new(self.ops.clone(), self.fns.clone())
    }

    pub fn query(
        &mut self,
        correlation_id: CorrelationId,
        base_key: Key,
        path: &[String],
    ) -> Result<QueryResult, R::Error> {
        let validated_key = Validated::new(base_key, Validated::valid)?;
        match self.read(correlation_id, &validated_key)? {
            None => Ok(QueryResult::ValueNotFound(self.error_path_msg(
                base_key,
                path,
                "".to_owned(),
                0 as usize,
            ))),
            Some(base_value) => {
                let result = path.iter().enumerate().try_fold(
                    base_value,
                    // We encode the two possible short-circuit conditions with
                    // Result<(usize, String), Error>, where the Ok(_) case corresponds to
                    // QueryResult::ValueNotFound and Err(_) corresponds to
                    // a storage-related error. The information in the Ok(_) case is used
                    // to build an informative error message about why the query was not successful.
                    |curr_value, (i, name)| -> Result<Value, Result<(usize, String), R::Error>> {
                        match curr_value {
                            Value::Account(account) => {
                                if let Some(key) = account.urefs_lookup().get(name) {
                                    let validated_key = Validated::new(*key, Validated::valid)?;
                                    self.read_key_or_stop(correlation_id, validated_key, i)
                                } else {
                                    Err(Ok((i, format!("Name {} not found in Account at path:", name))))
                                }
                            }

                            Value::Contract(contract) => {
                                if let Some(key) = contract.urefs_lookup().get(name) {
                                    let validated_key = Validated::new(*key, Validated::valid)?;
                                    self.read_key_or_stop(correlation_id, validated_key, i)
                                } else {
                                    Err(Ok((i, format!("Name {} not found in Contract at path:", name))))
                                }
                            }

                            other => Err(
                                Ok((i, format!("Name {} cannot be followed from value {:?} because it is neither an account nor contract. Value found at path:", name, other)))
                                ),
                        }
                    },
                );

                match result {
                    Ok(value) => Ok(QueryResult::Success(value)),
                    Err(Ok((i, s))) => Ok(QueryResult::ValueNotFound(
                        self.error_path_msg(base_key, path, s, i),
                    )),
                    Err(Err(err)) => Err(err),
                }
            }
        }
    }

    fn read_key_or_stop(
        &mut self,
        correlation_id: CorrelationId,
        key: Validated<Key>,
        i: usize,
    ) -> Result<Value, Result<(usize, String), R::Error>> {
        match self.read(correlation_id, &key) {
            // continue recursing
            Ok(Some(value)) => Ok(value),
            // key not found in the global state; stop recursing
            Ok(None) => Err(Ok((i, format!("Name {:?} not found: ", *key)))),
            // global state access error; stop recursing
            Err(error) => Err(Err(error)),
        }
    }

    fn error_path_msg(
        &self,
        key: Key,
        path: &[String],
        missing_key: String,
        missing_at_index: usize,
    ) -> String {
        let mut error_msg = format!("{} {:?}", missing_key, key);
        //include the partial path to the account/contract/value which failed
        for p in path.iter().take(missing_at_index) {
            error_msg.push_str("/");
            error_msg.push_str(p);
        }
        error_msg
    }
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::collections::BTreeMap;
    use std::iter;
    use std::rc::Rc;

    use proptest::collection::vec;
    use proptest::prelude::*;

    use common::gens::*;
    use common::key::Key;
    use common::uref::{AccessRights, URef};
    use common::value::{Account, Contract, Value};
    use shared::transform::Transform;
    use storage::global_state::in_memory::InMemoryGlobalState;
    use storage::global_state::StateReader;

    use super::{AddResult, QueryResult, Validated};
    use common::value::account::{
        AccountActivity, AssociatedKeys, BlockTime, PublicKey, PurseId, Weight, KEY_SIZE,
    };
    use engine_state::op::Op;
    use shared::newtypes::CorrelationId;
    use tracking_copy::TrackingCopy;

    struct CountingDb {
        count: Rc<Cell<i32>>,
        value: Option<Value>,
    }

    impl CountingDb {
        fn new(counter: Rc<Cell<i32>>) -> CountingDb {
            CountingDb {
                count: counter,
                value: None,
            }
        }

        fn new_init(v: Value) -> CountingDb {
            CountingDb {
                count: Rc::new(Cell::new(0)),
                value: Some(v),
            }
        }
    }

    impl StateReader<Key, Value> for CountingDb {
        type Error = !;
        fn read(
            &self,
            _correlation_id: CorrelationId,
            _key: &Key,
        ) -> Result<Option<Value>, Self::Error> {
            let count = self.count.get();
            let value = match self.value {
                Some(ref v) => v.clone(),
                None => Value::Int32(count),
            };
            self.count.set(count + 1);
            Ok(Some(value))
        }
    }

    #[test]
    fn tracking_copy_new() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let tc = TrackingCopy::new(db);

        assert_eq!(tc.cache.is_empty(), true);
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);
    }

    #[test]
    fn tracking_copy_caching() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let zero = Value::Int32(0);
        // first read
        let value = tc
            .read(
                correlation_id,
                &Validated::new(k, Validated::valid).unwrap(),
            )
            .unwrap()
            .unwrap();
        assert_eq!(value, zero);

        // second read; should use cache instead
        // of going back to the DB
        let value = tc
            .read(
                correlation_id,
                &Validated::new(k, Validated::valid).unwrap(),
            )
            .unwrap()
            .unwrap();
        let db_value = counter.get();
        assert_eq!(value, zero);
        assert_eq!(db_value, 1);
    }

    #[test]
    fn tracking_copy_read() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let zero = Value::Int32(0);
        let value = tc
            .read(
                correlation_id,
                &Validated::new(k, Validated::valid).unwrap(),
            )
            .unwrap()
            .unwrap();
        // value read correctly
        assert_eq!(value, zero);
        // read produces an identity transform
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Identity));
        // read does produce an op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Read));
    }

    #[test]
    fn tracking_copy_write() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let one = Value::Int32(1);
        let two = Value::Int32(2);

        // writing should work
        tc.write(
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(one.clone(), Validated::valid).unwrap(),
        );
        // write does not need to query the DB
        let db_value = counter.get();
        assert_eq!(db_value, 0);
        // write creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(one)));
        // write creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));

        // writing again should update the values
        tc.write(
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(two.clone(), Validated::valid).unwrap(),
        );
        let db_value = counter.get();
        assert_eq!(db_value, 0);
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(two)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_add_i32() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let three = Value::Int32(3);

        // adding should work
        let add = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(three.clone(), Validated::valid).unwrap(),
        );
        assert_matches!(add, Ok(_));

        // add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        // add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));

        // adding again should update the values
        let add = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(three, Validated::valid).unwrap(),
        );
        assert_matches!(add, Ok(_));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(6)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_add_named_key() {
        let correlation_id = CorrelationId::new();
        // DB now holds an `Account` so that we can test adding a `NamedKey`
        let associated_keys = AssociatedKeys::new(PublicKey::new([0u8; KEY_SIZE]), Weight::new(1));
        let account = common::value::Account::new(
            [0u8; KEY_SIZE],
            0u64,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            Default::default(),
            AccountActivity::new(BlockTime(0), BlockTime(100)),
        );
        let db = CountingDb::new_init(Value::Account(account));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);
        let u1 = Key::URef(URef::new([1u8; 32], AccessRights::READ_WRITE));
        let u2 = Key::URef(URef::new([2u8; 32], AccessRights::READ_WRITE));

        let named_key = Value::NamedKey("test".to_string(), u1);
        let other_named_key = Value::NamedKey("test2".to_string(), u2);
        let mut map: BTreeMap<String, Key> = BTreeMap::new();
        // This is written as an `if`, but it is clear from the line
        // where `named_key` is defined that it will always match
        if let Value::NamedKey(name, key) = named_key.clone() {
            map.insert(name, key);
        }

        // adding the wrong type should fail
        let failed_add = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(Value::Int32(3), Validated::valid).unwrap(),
        );
        assert_matches!(failed_add, Ok(AddResult::TypeMismatch(_)));
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);

        // adding correct type works
        let add = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(named_key, Validated::valid).unwrap(),
        );
        assert_matches!(add, Ok(_));
        // add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map.clone())));
        // add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));

        // adding again updates the values
        if let Value::NamedKey(name, key) = other_named_key.clone() {
            map.insert(name, key);
        }
        let add = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(other_named_key, Validated::valid).unwrap(),
        );
        assert_matches!(add, Ok(_));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_rw() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then writing should update the op
        let value = Value::Int32(3);
        let _ = tc.read(
            correlation_id,
            &Validated::new(k, Validated::valid).unwrap(),
        );
        tc.write(
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(value.clone(), Validated::valid).unwrap(),
        );
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(value)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_ra() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then adding should update the op
        let value = Value::Int32(3);
        let _ = tc.read(
            correlation_id,
            &Validated::new(k, Validated::valid).unwrap(),
        );
        let _ = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(value, Validated::valid).unwrap(),
        );
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        assert_eq!(tc.ops.len(), 1);
        // this Op is correct because Read+Add = Write
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_aw() {
        let correlation_id = CorrelationId::new();
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // adding then writing should update the op
        let value = Value::Int32(3);
        let write_value = Value::Int32(7);
        let _ = tc.add(
            correlation_id,
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(value, Validated::valid).unwrap(),
        );
        tc.write(
            Validated::new(k, Validated::valid).unwrap(),
            Validated::new(write_value.clone(), Validated::valid).unwrap(),
        );
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(write_value)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    proptest! {
        #[test]
        fn query_empty_path(k in key_arb(), missing_key in key_arb(), v in value_arb()) {
            let correlation_id = CorrelationId::new();
            let gs = InMemoryGlobalState::from_pairs(correlation_id, &[(k, v.to_owned())]).unwrap();
            let mut tc = TrackingCopy::new(gs);
            let empty_path = Vec::new();
            if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, k, &empty_path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_key != k {
                let result = tc.query(correlation_id, missing_key, &empty_path);
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
            }
        }

        #[test]
        fn query_contract_state(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in contract state
            name in "\\PC*", // human-readable name for state
            missing_name in "\\PC*",
            body in vec(any::<u8>(), 1..1000), // contract body
            hash in u8_slice_32(), // hash for contract key
        ) {
            let correlation_id = CorrelationId::new();
            let mut known_urefs = BTreeMap::new();
            known_urefs.insert(name.clone(), k);
            let contract: Value = Contract::new(body, known_urefs, 1).into();
            let contract_key = Key::Hash(hash);

            let gs = InMemoryGlobalState::from_pairs(
                correlation_id,
                &[(k, v.to_owned()), (contract_key, contract)]
            ).unwrap();
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(name.clone());
            if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, contract_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_name != name {
                let result = tc.query(correlation_id, contract_key, &[missing_name]);
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
            }
        }


        #[test]
        fn query_account_state(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in account state
            name in "\\PC*", // human-readable name for state
            missing_name in "\\PC*",
            pk in u8_slice_32(), // account public key
            nonce in any::<u64>(), // account nonce
            address in u8_slice_32(), // address for account key
        ) {
            let correlation_id = CorrelationId::new();
            let known_urefs = iter::once((name.clone(), k)).collect();
            let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
            let associated_keys = AssociatedKeys::new(PublicKey::new(pk), Weight::new(1));
            let account = Account::new(
                pk,
                nonce,
                known_urefs,
                purse_id,
                associated_keys,
                Default::default(),
                AccountActivity::new(BlockTime(0), BlockTime(100))
            );
            let account_key = Key::Account(address);

            let gs = InMemoryGlobalState::from_pairs(
                correlation_id,
                &[(k, v.to_owned()), (account_key, Value::Account(account))],
            ).unwrap();
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(name.clone());
            if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, account_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_name != name {
                let result = tc.query(correlation_id, account_key, &[missing_name]);
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
            }
        }

        #[test]
        fn query_path(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in contract state
            state_name in "\\PC*", // human-readable name for state
            contract_name in "\\PC*", // human-readable name for contract
            pk in u8_slice_32(), // account public key
            nonce in any::<u64>(), // account nonce
            address in u8_slice_32(), // address for account key
            body in vec(any::<u8>(), 1..1000), //contract body
            hash in u8_slice_32(), // hash for contract key
        ) {
            let correlation_id = CorrelationId::new();
            // create contract which knows about value
            let mut contract_known_urefs = BTreeMap::new();
            contract_known_urefs.insert(state_name.clone(), k);
            let contract: Value = Contract::new(body, contract_known_urefs, 1).into();
            let contract_key = Key::Hash(hash);

            // create account which knows about contract
            let mut account_known_urefs = BTreeMap::new();
            account_known_urefs.insert(contract_name.clone(), contract_key);
            let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
            let associated_keys = AssociatedKeys::new(PublicKey::new(pk), Weight::new(1));
            let account = Account::new(
                pk,
                nonce,
                account_known_urefs,
                purse_id,
                associated_keys,
                Default::default(),
                AccountActivity::new(BlockTime(0), BlockTime(100))
            );
            let account_key = Key::Account(address);

            let gs = InMemoryGlobalState::from_pairs(correlation_id, &[
                (k, v.to_owned()),
                (contract_key, contract),
                (account_key, Value::Account(account)),
            ]).unwrap();
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(contract_name, state_name);
            if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, account_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }
        }
    }
}

#[cfg(test)]
pub mod tracking_copy_cache {
    use common::key::Key;
    use common::value::Value;
    use meter::count_meter::Count;
    use tracking_copy::TrackingCopyCache;

    #[test]
    fn cache_reads_invalidation() {
        let mut tc_cache = TrackingCopyCache::new(2, Count);
        let (k1, v1) = (Key::Hash([1u8; 32]), Value::Int32(1));
        let (k2, v2) = (Key::Hash([2u8; 32]), Value::Int32(2));
        let (k3, v3) = (Key::Hash([3u8; 32]), Value::Int32(3));
        tc_cache.insert_read(k1, v1);
        tc_cache.insert_read(k2, v2.clone());
        tc_cache.insert_read(k3, v3.clone());
        assert!(tc_cache.get(&k1).is_none()); // first entry should be invalidated
        assert_eq!(tc_cache.get(&k2), Some(&v2)); // k2 and k3 should be there
        assert_eq!(tc_cache.get(&k3), Some(&v3));
    }

    #[test]
    fn cache_writes_not_invalidated() {
        let mut tc_cache = TrackingCopyCache::new(2, Count);
        let (k1, v1) = (Key::Hash([1u8; 32]), Value::Int32(1));
        let (k2, v2) = (Key::Hash([2u8; 32]), Value::Int32(2));
        let (k3, v3) = (Key::Hash([3u8; 32]), Value::Int32(3));
        tc_cache.insert_write(k1, v1.clone());
        tc_cache.insert_read(k2, v2.clone());
        tc_cache.insert_read(k3, v3.clone());
        // Writes are not subject to cache invalidation
        assert_eq!(tc_cache.get(&k1), Some(&v1));
        assert_eq!(tc_cache.get(&k2), Some(&v2)); // k2 and k3 should be there
        assert_eq!(tc_cache.get(&k3), Some(&v3));
    }
}
