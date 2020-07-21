package fr.acinq.eklair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eklair.MilliSatoshi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentRequestTestsCommon {

    @Test
    fun `Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad`() {
        println("1")
        val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertTrue(pr.amount == null)
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.description, "Please consider supporting this project")
        assertEquals(pr.tags.size,  2)
    }

    @Test
    fun `Please send $3 for a cup of coffee to the same peer, within 1 minute`() {
        println("2")
        val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertTrue(pr.amount == MilliSatoshi(250000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.description, "1 cup coffee")
        assertEquals(pr.tags.size,  3)
    }

    @Ignore
    fun `The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP`() {
        println("3")
        val ref = "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3x9et2e20v6pu37c5d9vax37wxq72un98k6vcx9fz94w0qf237cm2rqv9pmn5lnexfvf5579slr4zq3u8kmczecytdx0xg9rwzngp7e6guwqpqlhssu04sucpnz4axcv2dstmknqq6jsk2l"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lntb")
        assertTrue(pr.amount == MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        //description, "1 cup coffee")
        assertEquals(pr.tags.size,  3)
    }

    @Ignore
    fun `On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqj9n4evl6mr5aj9f58zp6fyjzup6ywn3x6sk8akg5v4tgn2q8g4fhx05wf6juaxu9760yp46454gpg5mtzgerlzezqcqvjnhjh8z3g2qqdhhwkj"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertTrue(pr.amount == MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  4)
    }

    @Ignore
    fun `On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kk822r8plup77n9yq5ep2dfpcydrjwzxs0la84v3tfw43t3vqhek7f05m6uf8lmfkjn7zv7enn76sq65d8u9lxav2pl6x3xnc2ww3lqpagnh0u"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertTrue(pr.amount == MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.description, "1 cup coffee")
        assertEquals(pr.tags.size,  3)
    }
}