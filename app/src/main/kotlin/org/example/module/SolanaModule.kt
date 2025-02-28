package org.example.module

import com.squareup.moshi.Moshi
import org.bitcoinj.core.Base58
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.p2p.solanaj.core.Account
import org.p2p.solanaj.core.PublicKey
import org.p2p.solanaj.core.Transaction
import org.p2p.solanaj.core.TransactionInstruction
import org.p2p.solanaj.programs.AssociatedTokenProgram
import org.p2p.solanaj.programs.SystemProgram
import org.p2p.solanaj.programs.TokenProgram
import org.p2p.solanaj.rpc.RpcClient
import org.p2p.solanaj.rpc.RpcException
import org.p2p.solanaj.rpc.types.RpcRequest
import org.web3j.crypto.MnemonicUtils
import java.math.BigDecimal

/**
 *packageName    : org.example.module
 * fileName       : SolanaModule
 * author         : mac
 * date           : 2/28/25
 * description    :
 * ===========================================================
 * DATE              AUTHOR             NOTE
 * -----------------------------------------------------------
 * 2/28/25        mac       최초 생성
 */

class SolanaModule {
    val rpcEndpoint = "https://api.devnet.solana.com"
    val associatedTokenProgramId = PublicKey("ATokenGPv1c2trTcbGnQ4WJNe48CwKM9DRJ8DRprz9x")
    val ACCOUNT_LAYOUT_SIZE:Long = 165L
    val connection: RpcClient

    init {
        connection = getConnection(rpcEndpoint)
    }
    private fun getConnection(rpcEndpoint: String): RpcClient {
        return RpcClient(rpcEndpoint)
    }

    /**
     * Retrieves the Solana account balance.
     * @param publicKey String Public key
     * @return BigDecimal
     */
    fun getSolanaBalance(publicKey: PublicKey): BigDecimal {
        println("getBalance")

        return try {
            val lamports = this.connection.api.getBalance(publicKey)
            BigDecimal(lamports).divide(BigDecimal(LAMPORTS_PER_SOL))
        } catch (e: Exception) {
            println("Error fetching balance: ${e.message}")
            BigDecimal.valueOf(0L)
        }
    }


    /**
     * Transfers Solana.
     * @param senderMnemonic String Sender's mnemonic
     * @param recipient String Recipient's public key address
     * @param amount BigDecimal Amount to send
     */
    fun solanaTransfer(senderMnemonic: String,
                       recipientPublicKey: String,
                       amount: BigDecimal
    ) : Transaction {
        if (amount < BigDecimal.valueOf(0L)) {
            throw IllegalArgumentException("Negative numbers are not allowed.")
        }

        // Generate public key
        val senderAccount = getSolanaAccount(senderMnemonic)
        // Retrieve Solana account balance
        val balance = getSolanaBalance(senderAccount.publicKey)

        if (balance < amount) {
            throw IllegalArgumentException("Insufficient balance")
        }

        val recipientPublicKey = PublicKey(recipientPublicKey)

        val transferInstruction = SystemProgram.transfer(
            senderAccount.getPublicKey(),
            recipientPublicKey,
            calculateAmountByPerSol(amount).toLong()
        )
        val transaction = Transaction()
        transaction.addInstruction(transferInstruction)

        // Sign and send the transaction
        val signature: String = try {
            this.connection.api.sendTransaction(transaction, senderAccount)
        } catch (e: RpcException) {
            throw IllegalStateException("Transaction failed: ${e.message}", e)
        }

        println("Transaction Signature: $signature")

        return transaction
    }


    /**
     * Retrieves the current SPL token balance.
     * @param secretKey String - Owner's secret key (payer)
     * @param userPublicKey String - User's wallet address to check balance
     * @param mint String - Mint address
     * @return BigDecimal
     */
    fun splTokenBalance(
        secretKey: String,
        userPublicKey: String,
        mint: String
    ): BigDecimal {

        val mintAddress = PublicKey(mint)
        // How to handle custom token programs?

        // Generate public key
        val generateSolanaAccount = getSolanaAccount(secretKey, "")

        return try {
            // Find or create the associated token account
            val tokenAccount = getOrCreateAssociatedTokenAccount(
                generateSolanaAccount,
                mintAddress,
                PublicKey(userPublicKey),
                false,
                "confirmed",
                TokenProgram.PROGRAM_ID
            )

            val amount = if(tokenAccount.second >= BigDecimal.valueOf(0L)){
                tokenAccount.second.divide(BigDecimal.valueOf(LAMPORTS_PER_SOL))
            } else {
                BigDecimal.valueOf(0L)
            }
            println("tokenAccount.second  " + amount)
            return amount
        } catch (e: Exception) {
            println("Error fetching SPL token balance: ${e.message}")
            return BigDecimal.valueOf(0L)
        }
    }

    /**
     * SPL token transfer service.
     * @param secretKey String
     * @param recipient String
     * @param mint String
     * @param amount BigDecimal
     * @return Transaction
     */
    fun splTokenTransfer(secretKey: String,
                         recipient: String,
                         mint: String,
                         amount: BigDecimal
    ): Transaction {
        if (amount < BigDecimal.valueOf(0L)) {
            throw IllegalArgumentException("Negative numbers are not allowed.")
        }

        // Generate public key
        val senderKeypair = getSolanaAccount(secretKey)

        // Fetch SPL token account balance
        val balance = try {
            splTokenBalance(
                secretKey,
                senderKeypair.publicKey.toBase58(),
                mint
            )
        } catch (e: RpcException) {
            throw IllegalStateException("Error fetching SPL token balance: ${e.message}", e)
        }

        println("Balance: $balance")
        println("Request amount: ${amount}")

        if (balance < amount) {
            throw IllegalArgumentException("Insufficient balance")
        }

        // Create transaction
        val transaction = Transaction()
        val mintAddress = PublicKey(mint)
        val senderAccount = getOrCreateAssociatedTokenAccount(
            senderKeypair,
            mintAddress,
            senderKeypair.publicKey,
            false,
            "confirmed",
            TokenProgram.PROGRAM_ID
        )

        val recipientAccount = getOrCreateAssociatedTokenAccount(
            senderKeypair,
            mintAddress,
            PublicKey(recipient),
            false,
            "confirmed",
            TokenProgram.PROGRAM_ID
        )

        val transferInstruction = TokenProgram.transferChecked(
            senderAccount.first,
            recipientAccount.first,
            amount.multiply(BigDecimal.valueOf(LAMPORTS_PER_SOL)).toLong(),
            9,
            senderKeypair.publicKey,
            mintAddress
        )

        transaction.addInstruction(transferInstruction)

        // Sign and send the transaction
        val signature: String = try {
            this.connection.api.sendTransaction(transaction, senderKeypair)
        } catch (e: RpcException) {
            throw IllegalStateException("Transaction failed: ${e.message}", e)
        }

        println("Transaction Signature: $signature")

        return transaction
    }

    private fun calculateAmountByPerSol(amount : BigDecimal) : BigDecimal {
        return if(amount.compareTo(BigDecimal.valueOf(0L))<=0){
            BigDecimal.valueOf(0L)
        }else{
            amount.multiply(BigDecimal.valueOf(LAMPORTS_PER_SOL))
        }
    }
    // fun mintNft(){
    //     try {
    //         // Solana 네트워크 클라이언트 설정ㅂㅈㄱㅂㅈㄱ
    //         val client = RpcClient("https://api.devnet.solana.com")
    //
    //         // 각 파라미터를 PublicKey 객체로 변환
    //         val collectionMint = PublicKey(get)
    //         val candyMachine = PublicKey(request.candyMachine)
    //         val candyGuard = PublicKey(request.candyGuard)
    //         val nftMint = Account() // 새로운 NFT Mint 계정 생성
    //         val collectionUpdateAuthority = keypair.publicKey
    //
    //         // 트랜잭션 생성
    //         val transaction = Transaction()
    //
    //         // Candy Machine과 Candy Guard를 사용한 NFT 민팅 트랜잭션 추가
    //         transaction.addInstruction(
    //             TransactionInstruction(
    //                 PublicKey("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"), // Metaplex Program ID
    //                 listOf(
    //                     collectionMint,  // 컬렉션 NFT 주소
    //                     candyMachine,    // Candy Machine ID
    //                     candyGuard,      // Candy Guard ID
    //                     nftMint.publicKey,  // 새로 민팅할 NFT 주소
    //                     collectionUpdateAuthority  // 컬렉션 업데이트 권한자
    //                 ),
    //                 "mintV2".toByteArray() // `mintV2` RPC 호출
    //             )
    //         )
    //
    //         // SOL 결제 (Minting 비용 전송)
    //         transaction.addInstruction(
    //             SystemProgram.transfer(
    //                 keypair.publicKey,
    //                 PublicKey("YOUR_DESTINATION_ADDRESS"), // 결제 수신 주소
    //                 1_000_000 // 0.001 SOL (테스트용)
    //             )
    //         )
    //
    //         // 트랜잭션 서명 및 전송
    //         transaction.sign(keypair)
    //         val signature = client.api.sendTransaction(transaction)
    //
    //         println("NFT Mint Transaction Signature: $signature")
    //         return signature
    //     } catch (e: RpcException) {
    //         println("Error: ${e.message}")
    //         throw e
    //     }
    // }

    /**
     * Mints an NFT.
     * @param secretKey String
     * @param collection String
     * @param candyMachine String
     * @param candyGuard String
     */
    fun mintNft(secretKey: String, collection: String, candyMachine: String, candyGuard: String){
        try {
            val decodedKey = Base58.decode(secretKey)
            val account = Account(decodedKey)

            // Generate public key
            val senderKeypair = getSolanaAccount(secretKey)

            val nftMint = Account() // Create a new Keypair for minting a new NFT

            val mintRequest = buildMintTransaction(
                account,
                nftMint,
                collection,
                candyMachine,
                candyGuard
            )

            val result = this.connection.call(mintRequest.method,mintRequest.params, Moshi::class.java)

        } catch (e: Exception) {
            println("Error minting NFT: ${e.message}")
        }
    }

    /**
     *
     * @param payer Account
     * @param nftMint Account
     * @param collection String
     * @param candyMachine String
     * @param candyGuard String
     * @return RpcRequest
     */
    private fun buildMintTransaction(
        payer: Account,
        nftMint: Account,
        collection: String,
        candyMachine: String,
        candyGuard: String
    ): RpcRequest {
        val mintArgs = mapOf("solPayment" to mapOf("destination" to payer.publicKey.toBase58()))

        return RpcRequest(
            "mintV2",
            listOf(
                mapOf(
                    "collectionMint" to collection,
                    "candyMachine" to candyMachine,
                    "candyGuard" to candyGuard,
                    "nftMint" to nftMint.publicKey.toBase58(),
                    "collectionUpdateAuthority" to payer.publicKey.toBase58(),
                    "mintArgs" to mintArgs
                )
            )
        )
    }

    /**
     * Retrieves token wallet information. If it does not exist, a token address is created.
     * @param payer Account - Paying account
     * @param mint PublicKey - Mint address
     * @param owner PublicKey - Token owner address
     * @param allowOwnerOffCurve Boolean
     * @param commitment String
     * @param programId PublicKey
     * @return Pair<PublicKey, BigInteger>
     */
    fun getOrCreateAssociatedTokenAccount(
        payer: Account,
        mint: PublicKey,
        owner: PublicKey,
        allowOwnerOffCurve: Boolean = false,
        commitment: String = "confirmed",
        programId: PublicKey = TokenProgram.PROGRAM_ID
    ): Pair<PublicKey, BigDecimal> {

        // Calculate the associated token account (ATA) address
        val associatedTokenAddress = PublicKey.findProgramAddress(
            listOf(
                owner.toByteArray(),
                TokenProgram.PROGRAM_ID.toByteArray(),
                mint.toByteArray()
            ),
            AssociatedTokenProgram.PROGRAM_ID
        )
        println("associatedTokenAddress : " + associatedTokenAddress.address)
        // Check if the ATA exists
        val accountInfo = this.connection.api.getAccountInfo(associatedTokenAddress.address)
        println("accountInfo = " + accountInfo);
        if (accountInfo.value != null) {
            // Fetch balance if ATA exists
            val tokenBalance = this.connection.api.getTokenAccountBalance(associatedTokenAddress.address)
            return Pair(associatedTokenAddress.address, BigDecimal(tokenBalance.amount))

        }

        // If ATA doesn't exist, create it
        val transaction = Transaction()
        val create = AssociatedTokenProgram.createIdempotent(payer.publicKey, owner, mint)
        transaction.addInstruction(
            create
        )
        try {
            val signature: String = this.connection.api.sendTransaction(transaction, payer)

        }catch (e: RpcException){
            println(e.message)
        }

        return Pair(associatedTokenAddress.address, BigDecimal.ZERO)
    }

    /**
     * Generates an account from the given mnemonic.
     * @param mnemonic String Mnemonic address
     * @param passphrase String Password
     * @return Account Account information derived from mnemonic
     */
    private fun getSolanaAccount(mnemonic: String, passphrase: String = ""): Account {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")

        // Generate Ed25519 private key (first 32 bytes)
        val privateKey = seed.copyOf(32)
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)

        // Generate public key
        val publicKey = privateKeyParams.generatePublicKey().encoded

        // Create Solana account
        return Account(privateKey + publicKey)
    }

    companion object {
        private const val LAMPORTS_PER_SOL = 1_000_000_000L // 1 SOL = 1 billion lamports
    }

}