package net.postchain.eif.bad

import net.postchain.common.toHex
import net.postchain.eif.EventMerkleProof
import net.postchain.eif.contracts.TokenBridge
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

/**
 * EventMerkleProof
 */
fun EventMerkleProof.web3EventData() = DynamicBytes(eventData)

fun EventMerkleProof.web3EventProof() = TokenBridge.Proof(
        Bytes32(this.eventProof!!.leaf),
        // Don't delete !! to help the compiler with a smart cast, otherwise you will get
        // `Smart cast to '...' is impossible, because '...' is a public API property declared in different module
        Uint256(this.eventProof!!.position),
        DynamicArray(Bytes32::class.java, this.eventProof!!.merkleProofs.map { Bytes32(it) })
)

fun EventMerkleProof.web3BlockHeader() = DynamicBytes(blockHeader)

fun EventMerkleProof.web3Signatures() = DynamicArray(DynamicBytes::class.java, blockWitness!!.map { DynamicBytes(it.sig) })

fun EventMerkleProof.web3Signers() = DynamicArray(Address::class.java, blockWitness!!.map { Address(it.pubkey.toHex()) })

fun EventMerkleProof.web3ExtraProofData() = TokenBridge.ExtraProofData(
        DynamicBytes(extraMerkleProof!!.leaf),
        Bytes32(extraMerkleProof!!.hashedLeaf),
        Uint256(extraMerkleProof!!.position),
        Bytes32(extraMerkleProof!!.extraRoot),
        DynamicArray(Bytes32::class.java, extraMerkleProof!!.extraMerkleProofs.map { Bytes32(it) })
)
