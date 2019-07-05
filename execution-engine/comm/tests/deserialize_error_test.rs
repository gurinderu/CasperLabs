extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::value::account::PublicKey;
use test_support::WasmTestBuilder;

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_not_fail_deserializing() {
    let is_error = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "deserialize_error.wasm",
            1,
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .commit()
        .is_error();

    assert!(is_error);
}
