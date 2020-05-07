package com.mycelium.wapi.wallet.erc20

import com.mrd.bitlib.crypto.InMemoryPrivateKey
import com.mrd.bitlib.util.HexUtils
import com.mycelium.net.HttpsEndpoint
import com.mycelium.wapi.wallet.*
import com.mycelium.wapi.wallet.btc.FeePerKbFee
import com.mycelium.wapi.wallet.coins.Balance
import com.mycelium.wapi.wallet.coins.Value
import com.mycelium.wapi.wallet.erc20.coins.ERC20Token
import com.mycelium.wapi.wallet.eth.*
import com.mycelium.wapi.wallet.exceptions.GenericBuildTransactionException
import com.mycelium.wapi.wallet.exceptions.GenericInsufficientFundsException
import com.mycelium.wapi.wallet.genericdb.EthAccountBacking
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.crypto.TransactionUtils
import org.web3j.tx.Transfer
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Level


class ERC20Account(private val accountContext: ERC20AccountContext,
                   private val token: ERC20Token,
                   private val ethAcc: EthAccount,
                   credentials: Credentials,
                   backing: EthAccountBacking,
                   private val accountListener: AccountListener?,
                   web3jWrapper: Web3jWrapper,
                   private val transactionServiceEndpoints: List<HttpsEndpoint>) : AbstractEthERC20Account(accountContext.currency, credentials,
        backing, ERC20Account::class.simpleName, web3jWrapper) {
    private var removed = false

    override fun createTx(address: GenericAddress, amount: Value, fee: GenericFee, data: GenericTransactionData?): GenericTransaction {
        val ethTxData = (data as? EthTransactionData)
        val gasLimit = ethTxData?.gasLimit ?: BigInteger.valueOf(90_000)
        val gasPrice = (fee as FeePerKbFee).feePerKb.value
        val nonce = getNewNonce(receivingAddress)
        val inputData = getInputData(address.toString(), amount.value)

        if (calculateMaxSpendableAmount(null, null) < amount) {
            throw GenericInsufficientFundsException(Throwable("Insufficient funds"))
        }
        if (gasLimit < typicalEstimatedTransactionSize.toBigInteger()) {
            throw GenericBuildTransactionException(Throwable("Gas limit must be at least 21000"))
        }
        if (ethAcc.accountBalance.spendable.value < gasPrice * gasLimit) {
            throw GenericInsufficientFundsException(Throwable("Insufficient funds on eth account to pay for fee"))
        }

        return EthTransaction(coinType, address.toString(), Value.zeroValue(basedOnCoinType),
                gasPrice, nonce, gasLimit, inputData)
    }

    private fun getInputData(address: String, value: BigInteger): String {
        val function = org.web3j.abi.datatypes.Function(
                StandardToken.FUNC_TRANSFER,
                listOf(Address(address),
                        Uint256(value)), emptyList())
        return FunctionEncoder.encode(function)
    }

    override fun signTx(request: GenericTransaction, keyCipher: KeyCipher) {
        val ethTx = (request as EthTransaction)
        val rawTransaction = RawTransaction.createTransaction(ethTx.nonce, ethTx.gasPrice, ethTx.gasLimit,
                token.contractAddress, ethTx.value.value, ethTx.inputData)
        val signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials)
        val hexValue = Numeric.toHexString(signedMessage)
        request.signedHex = hexValue
        request.txHash = TransactionUtils.generateTransactionHash(rawTransaction, credentials)
        request.txBinary = TransactionEncoder.encode(rawTransaction)!!
    }

    override fun broadcastTx(tx: GenericTransaction): BroadcastResult {
        try {
            val result = ERC20TransactionService(receiveAddress.addressString, transactionServiceEndpoints,
                    token.contractAddress).sendTransaction((tx as EthTransaction).signedHex!!)
                    ?: return BroadcastResult(BroadcastResultType.REJECTED)
            backing.putTransaction(-1, System.currentTimeMillis() / 1000, "0x" + HexUtils.toHex(tx.txHash),
                    tx.signedHex!!, receivingAddress.addressString, tx.toAddress,
                    Value.valueOf(basedOnCoinType, tx.value.value), Value.valueOf(basedOnCoinType, tx.gasPrice * tx.gasLimit), 0,
                    accountContext.nonce, tx.gasLimit, tx.gasLimit)
            return BroadcastResult(BroadcastResultType.SUCCESS)
        } catch (e: Exception) {
            return when (e) {
                is IOException -> BroadcastResult(BroadcastResultType.NO_SERVER_CONNECTION)
                else -> {
                    logger.log(Level.SEVERE, "Error sending ERC-20 transaction: ${e.localizedMessage}")
                    BroadcastResult(e.localizedMessage, BroadcastResultType.REJECT_INVALID_TX_PARAMS)
                }
            }
        }
    }

    override fun getCoinType() = token

    override fun getBasedOnCoinType() = accountContext.currency

    override fun getAccountBalance() = readBalance()

    override fun getLabel(): String = accountContext.accountName

    override fun setLabel(label: String?) {
        accountContext.accountName = label!!
    }

    @Synchronized
    override fun doSynchronization(mode: SyncMode?): Boolean {
        if (removed || isArchived) {
            return false
        }
        syncTransactions()
        return updateBalanceCache()
    }

    override fun getNonce() = accountContext.nonce

    override fun setNonce(nonce: BigInteger) {
        accountContext.nonce = nonce
    }

    override fun setBlockChainHeight(height: Int) {
        accountContext.blockHeight = height
    }

    override fun getBlockChainHeight() = accountContext.blockHeight

    override fun isArchived() = accountContext.archived

    override fun isActive() = !isArchived

    override fun archiveAccount() {
        accountContext.archived = true
        dropCachedData()
    }

    override fun activateAccount() {
        accountContext.archived = false
        dropCachedData()
    }

    override fun dropCachedData() {
        clearBacking()
        saveBalance(Balance.getZeroBalance(coinType))
    }

    override fun isVisible() = true

    override fun isDerivedFromInternalMasterseed() = false

    override fun getId(): UUID = accountContext.uuid

    override fun broadcastOutgoingTransactions() = true

    override fun calculateMaxSpendableAmount(minerFeePerKilobyte: Value?, destinationAddress: EthAddress?): Value =
            accountBalance.spendable

    override fun getSyncTotalRetrievedTransactions() = 0 // TODO implement after full transaction history implementation

    override fun getTypicalEstimatedTransactionSize() = Transfer.GAS_LIMIT.toInt()

    override fun getPrivateKey(cipher: KeyCipher?): InMemoryPrivateKey {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun updateBalanceCache(): Boolean {
        val pendingReceiving = getPendingReceiving()
        val pendingSending = getPendingSending()
        val newBalance = Balance(Value.valueOf(basedOnCoinType, getConfirmed() - pendingSending),
                Value.valueOf(basedOnCoinType, pendingReceiving), Value.valueOf(basedOnCoinType, pendingSending), Value.zeroValue(basedOnCoinType))
        if (newBalance != accountContext.balance) {
            accountContext.balance = newBalance
            accountListener?.balanceUpdated(this)
            return true
        }
        return false
    }

    private fun getConfirmed(): BigInteger = getTransactionSummaries(0, Int.MAX_VALUE)
            .filter { it.confirmations > 0 }
            .map { it.transferred.value }
            .fold(BigInteger.ZERO, BigInteger::add)

    private fun getPendingReceiving(): BigInteger = backing.getUnconfirmedTransactions(receivingAddress.addressString)
            .filter {
                !it.sender.addressString.equals(receiveAddress.addressString, true)
                        && it.receiver.addressString.equals(receiveAddress.addressString, true)
            }
            .map { it.value.value }
            .fold(BigInteger.ZERO, BigInteger::add)

    private fun getPendingSending(): BigInteger = backing.getUnconfirmedTransactions(receivingAddress.addressString)
            .filter {
                it.sender.addressString.equals(receiveAddress.addressString, true)
                        && !it.receiver.addressString.equals(receiveAddress.addressString, true)
            }
            .map { it.value.value }
            .fold(BigInteger.ZERO, BigInteger::add)

    private fun syncTransactions() {
        try {
            val remoteTransactions = ERC20TransactionService(receiveAddress.addressString, transactionServiceEndpoints,
                    token.contractAddress).getTransactions()
            remoteTransactions.filter { tx -> tx.getTokenTransfer(token.contractAddress) != null }.forEach { tx ->
                val transfer = tx.getTokenTransfer(token.contractAddress)!!
                backing.putTransaction(tx.blockHeight.toInt(), tx.blockTime, tx.txid, "", transfer.from,
                        transfer.to, Value.valueOf(basedOnCoinType, transfer.value),
                        Value.valueOf(basedOnCoinType, tx.gasPrice * (tx.gasUsed
                                ?: typicalEstimatedTransactionSize.toBigInteger())),
                        tx.confirmations.toInt(), tx.nonce, tx.gasLimit, tx.gasUsed)
            }
            val localTxs = getUnconfirmedTransactions()
            // remove such transactions that are not on server anymore
            // this could happen if transaction was replaced by another e.g.
            val toRemove = localTxs.filter { localTx ->
                !remoteTransactions.map { it.txid }.contains("0x" + HexUtils.toHex(localTx.id))
                        && (System.currentTimeMillis() / 1000 - localTx.timestamp > TimeUnit.SECONDS.toSeconds(150))
            }
            toRemove.map { "0x" + HexUtils.toHex(it.id) }.forEach {
                backing.deleteTransaction(it)
            }
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "Error retrieving ETH/ERC-20 transaction history: ${e.javaClass} ${e.localizedMessage}")
        }
    }

    // the following two wrappers are needed because we can't store balance in db with ERC20 coin type
    // we only can store balance in db with coinType within cryptocurrencies list
    // (refer to com.mycelium.wapi.wallet.coins.COIN for the list)
    // but on different activities we need balance with ERC20 coin type, therefore we need to convert
    // coinType -> basedOnCoinType (which is ETH for ERC20 and it's in the cryptocurrencies list)
    // all the time we going to store it in db, and basedOnCoinType -> coinType when we read from db
    private fun saveBalance(balance: Balance) {
        accountContext.balance = Balance(Value.valueOf(basedOnCoinType, balance.confirmed.value),
                Value.valueOf(basedOnCoinType, balance.pendingReceiving.value),
                Value.valueOf(basedOnCoinType, balance.pendingSending.value),
                Value.valueOf(basedOnCoinType, 0))
    }

    private fun readBalance(): Balance {
        val balance = accountContext.balance
        return Balance(Value.valueOf(coinType, balance.confirmed.value),
                Value.valueOf(coinType, balance.pendingReceiving.value),
                Value.valueOf(coinType, balance.pendingSending.value),
                Value.valueOf(coinType, 0))
    }
}