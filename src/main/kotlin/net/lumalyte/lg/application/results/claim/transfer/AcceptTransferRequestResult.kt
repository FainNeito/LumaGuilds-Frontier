package net.lumalyte.lg.application.results.claim.transfer

import net.lumalyte.lg.application.results.common.TextValidationErrorResult

sealed class AcceptTransferRequestResult {
    data object BufferConflict : AcceptTransferRequestResult()
    data object RaidLocked : AcceptTransferRequestResult()
    object Success : AcceptTransferRequestResult()
    object ClaimNotFound : AcceptTransferRequestResult()
    object NoActiveTransferRequest: AcceptTransferRequestResult()
    object ClaimLimitExceeded: AcceptTransferRequestResult()
    object BlockLimitExceeded: AcceptTransferRequestResult()
    object PlayerOwnsClaim: AcceptTransferRequestResult()
    object NameAlreadyExists: AcceptTransferRequestResult()
    data class InputTextInvalid(val errors: List<TextValidationErrorResult>) : AcceptTransferRequestResult()
    object StorageError : AcceptTransferRequestResult()
}
