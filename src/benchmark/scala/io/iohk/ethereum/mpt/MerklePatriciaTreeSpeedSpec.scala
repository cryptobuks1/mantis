package io.iohk.ethereum.mpt

import io.iohk.ethereum.db.dataSource.EphemDataSource
import io.iohk.ethereum.db.storage.{ArchiveNodeStorage, MptStorage, NodeStorage, SerializingMptStorage}
import io.iohk.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import io.iohk.ethereum.utils.Logger
import io.iohk.ethereum.{ObjectGenerators, crypto}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class MerklePatriciaTreeSpeedSpec
    extends AnyFunSuite
    with ScalaCheckPropertyChecks
    with ObjectGenerators
    with Logger
    with PersistentStorage {

  test("Performance test (From: https://github.com/ethereum/wiki/wiki/Benchmarks)") {
    val Rounds = 1000
    val Symmetric = true

    val start: Long = System.currentTimeMillis
    val emptyTrie = MerklePatriciaTrie[Array[Byte], Array[Byte]](
      new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    )
    var seed: Array[Byte] = Array.fill(32)(0.toByte)

    val trieResult = (0 until Rounds).foldLeft(emptyTrie) { case (recTrie, i) =>
      seed = Node.hashFn(seed)
      if (!Symmetric) recTrie.put(seed, seed)
      else {
        val mykey = seed
        seed = Node.hashFn(seed)
        val myval = if ((seed(0) & 0xff) % 2 == 1) Array[Byte](seed.last) else seed
        recTrie.put(mykey, myval)
      }
    }
    val rootHash = Hex.toHexString(trieResult.getRootHash)

    log.info("Time taken(ms): " + (System.currentTimeMillis - start))
    log.info("Root hash obtained: " + rootHash)

    if (Symmetric) assert(rootHash.take(4) == "36f6" && rootHash.drop(rootHash.length - 4) == "93a3")
    else assert(rootHash.take(4) == "da8a" && rootHash.drop(rootHash.length - 4) == "0ca4")
  }

  test("MPT benchmark with RocksDb") {
    withRocksDbNodeStorage { ns =>
      mptBenchmarkTest(ns)
    }
  }

  def mptBenchmarkTest(ns: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] = {
    val hashFn = crypto.kec256(_: Array[Byte])

    val defaultByteArraySer = MerklePatriciaTrie.defaultByteArraySerializable
    val EmptyTrie = MerklePatriciaTrie[Array[Byte], Array[Byte]](ns)(defaultByteArraySer, defaultByteArraySer)

    var t = System.currentTimeMillis()
    (1 to 20000000).foldLeft(EmptyTrie) { case (trie, i) =>
      val k = hashFn(("hello" + i).getBytes)
      val v = hashFn(("world" + i).getBytes)

      if (i % 100000 == 0) {
        val newT = System.currentTimeMillis()
        val delta = (newT - t) / 1000.0
        t = newT
        log.debug(s"=== $i elements put, time for batch is: $delta sec")
      }
      trie.put(k, v)
    }
  }
}
