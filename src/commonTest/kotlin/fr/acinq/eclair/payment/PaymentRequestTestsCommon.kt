package fr.acinq.eclair.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentRequestTestsCommon {
    val priv = PrivateKey(Hex.decode("e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734"))
    val pub = priv.publicKey()
    val nodeId = pub
    init {
        assertEquals(nodeId, PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    }

    @Test
    fun `Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad`() {
        val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, null)
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.description, "Please consider supporting this project")
        assertEquals(pr.tags.size,  2)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `Please send $3 for a cup of coffee to the same peer, within 1 minute`() {
        val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(250000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.description, "1 cup coffee")
        assertEquals(pr.tags.size,  3)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `Now send $24 for an entire list of things (hashed)`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqscc6gd6ql3jrc5yzme8v4ntcewwz5cnw92tz0pc8qcuufvq7khhr8wpald05e92xw006sq94mg8v2ndf4sefvf9sygkshp5zfem29trqq2yxxz7"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.descriptionHash, Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".encodeToByteArray()).toByteVector32())
        assertEquals(pr.tags.size,  2)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP`() {
        val ref = "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3x9et2e20v6pu37c5d9vax37wxq72un98k6vcx9fz94w0qf237cm2rqv9pmn5lnexfvf5579slr4zq3u8kmczecytdx0xg9rwzngp7e6guwqpqlhssu04sucpnz4axcv2dstmknqq6jsk2l"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lntb")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  3)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqj9n4evl6mr5aj9f58zp6fyjzup6ywn3x6sk8akg5v4tgn2q8g4fhx05wf6juaxu9760yp46454gpg5mtzgerlzezqcqvjnhjh8z3g2qqdhhwkj"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  4)
        val nodeIds = pr.routingInfo.flatMap { it.hints.map { it.nodeId } }
        assertEquals(listOf(PublicKey.fromHex("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), PublicKey.fromHex("039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255")), nodeIds)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kk822r8plup77n9yq5ep2dfpcydrjwzxs0la84v3tfw43t3vqhek7f05m6uf8lmfkjn7zv7enn76sq65d8u9lxav2pl6x3xnc2ww3lqpagnh0u"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  3)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppqw508d6qejxtdg4y5r3zarvary0c5xw7kknt6zz5vxa8yh8jrnlkl63dah48yh6eupakk87fjdcnwqfcyt7snnpuz7vp83txauq4c60sys3xyucesxjf46yqnpplj0saq36a554cp9wt865"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  3)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3`() {
        val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qvnjha2auylmwrltv2pkp2t22uy8ura2xsdwhq5nm7s574xva47djmnj2xeycsu7u5v8929mvuux43j0cqhhf32wfyn2th0sv4t9x55sppz5we8"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  3)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12`() {
        val ref = "lnbc20m1pvjluezcqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q90qkf3gd7fcqs0ewr7t3xf72ptmc4n38evg0xhy4p64nlg7hgrmq6g997tkrvezs8afs0x0y8v4vs8thwsk6knkvdfvfa7wmhhpcsxcqw0ny48"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2000000000))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        assertEquals(pr.tags.size,  4)
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, please send $30 for coffee beans to the same peer, which supports features 9, 15 and 99, using secret 0x1111111111111111111111111111111111111111111111111111111111111111`() {
        val refs = listOf(
            "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu",
            // All upper-case
            "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu".toUpperCase(),
            // With ignored fields
            "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq2jxxfsnucm4jf4zwtznpaxphce606fvhvje5x7d4gw7n73994hgs7nteqvenq8a4ml8aqtchv5d9pf7l558889hp4yyrqv6a7zpq9fgpskqhza"
        )
        refs.forEach { ref ->
            val pr = PaymentRequest.read(ref)
            assertEquals(pr.prefix, "lnbc")
            assertEquals(pr.amount, MilliSatoshi(2500000000L))
            assertEquals(pr.paymentHash, ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
            assertEquals(pr.timestamp, 1496314658L)
            assertEquals(pr.nodeId, PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
            assertEquals(pr.paymentSecret, ByteVector32("0x1111111111111111111111111111111111111111111111111111111111111111"))
            val check = pr.sign(priv).write()
            assertEquals(ref.toLowerCase(), check)
        }
    }

    @Test
    fun `On mainnet, please send $30 for coffee beans to the same peer, which supports features 9, 15, 99 and 100, using secret 0x1111111111111111111111111111111111111111111111111111111111111111`() {
        val ref = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(2500000000L))
        assertEquals(pr.paymentHash , ByteVector32("0001020304050607080900010203040506070809000102030405060708090102"))
        assertEquals(pr.timestamp , 1496314658L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

    @Test
    fun `On mainnet, please send 0,00967878534 BTC for a list of items within one week, amount in pico BTC`() {
        val ref = "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9qn07ytgrxxzad9hc4xt3mawjjt8znfv8xzscs7007v9gh9j569lencxa8xeujzkxs0uamak9aln6ez02uunw6rd2ht2sqe4hz8thcdagpleym0j"
        val pr = PaymentRequest.read(ref)
        assertEquals(pr.prefix , "lnbc")
        assertEquals(pr.amount, MilliSatoshi(967878534))
        assertEquals(pr.paymentHash , ByteVector32("462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f"))
        assertEquals(pr.timestamp , 1572468703L)
        assertEquals(pr.nodeId , PublicKey.fromHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
        val check = pr.sign(priv).write()
        assertEquals(ref, check)
    }

}