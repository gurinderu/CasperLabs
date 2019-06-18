use failure::Fail;

use shared::newtypes::Blake2bHash;

#[derive(Fail, Debug)]
pub enum Error {
    #[fail(display = "{}", _0)]
    PreprocessingError(String),
    #[fail(display = "Authorization failure: Keys do not belong to an account.")]
    AuthorizationFailure,
    #[fail(display = "Execution error")]
    ExecError(::execution::Error),
    #[fail(display = "Storage error")]
    StorageError(storage::error::Error),
}

impl From<wasm_prep::PreprocessingError> for Error {
    fn from(error: wasm_prep::PreprocessingError) -> Self {
        match error {
            wasm_prep::PreprocessingError::InvalidImportsError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::NoExportSection => {
                Error::PreprocessingError(String::from("No export section found."))
            }
            wasm_prep::PreprocessingError::NoImportSection => {
                Error::PreprocessingError(String::from("No import section found,"))
            }
            wasm_prep::PreprocessingError::DeserializeError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::OperationForbiddenByGasRules => {
                Error::PreprocessingError(String::from("Encountered operation forbidden by gas rules. Consult instruction -> metering config map."))
            }
            wasm_prep::PreprocessingError::StackLimiterError => {
                Error::PreprocessingError(String::from("Wasm contract error: Stack limiter error."))

            }
        }
    }
}

impl From<storage::error::Error> for Error {
    fn from(error: storage::error::Error) -> Self {
        Error::StorageError(error)
    }
}

impl From<::execution::Error> for Error {
    fn from(error: ::execution::Error) -> Self {
        Error::ExecError(error)
    }
}

impl From<!> for Error {
    fn from(error: !) -> Self {
        match error {}
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct RootNotFound(pub Blake2bHash);
