package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.secp256k1.Hex
import org.kodein.memory.text.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitmentsTestsCommon : EclairTestSuite() {
    private val logger by eclairLogger()

    @Test
    fun `reach normal state`() {
        reachNormal()
    }

    @Test
    fun `decrypt channel state`() {
        val raw = "9f781e66722e6163696e712e65636c6169722e6368616e6e656c2e4e6f726d616cbf6c737461746963506172616d73bf6a6e6f6465506172616d73bf6a6b65794d616e616765729f782666722e6163696e712e65636c6169722e63727970746f2e4c6f63616c4b65794d616e61676572bf647365656478406263316136616335396438656261336139363132613865623933303861316334316332303437396133366637323364643863663961363462303637633666303969636861696e48617368784034333439376664376638323639353731303866346133306664396365633361656261373939373230383465393065616430316561333330393030303030303030ffff65616c6961736770686f656e6978686665617475726573bf696163746976617465649fbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e4f7074696f6e446174614c6f737350726f74656374bfffff67737570706f7274694d616e6461746f7279ffbf67666561747572659f782b66722e6163696e712e65636c6169722e466561747572652e5661726961626c654c656e6774684f6e696f6ebfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782566722e6163696e712e65636c6169722e466561747572652e5061796d656e74536563726574bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e42617369634d756c7469506172745061796d656e74bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f781d66722e6163696e712e65636c6169722e466561747572652e57756d626fbfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782766722e6163696e712e65636c6169722e466561747572652e53746174696352656d6f74654b6579bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782966722e6163696e712e65636c6169722e466561747572652e5472616d706f6c696e655061796d656e74bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782566722e6163696e712e65636c6169722e466561747572652e416e63686f724f757470757473bfffff67737570706f7274684f7074696f6e616cffffff69647573744c696d69741902226e6f6e436861696e466565436f6e66bf76636c6f73654f6e4f66666c696e654d69736d61746368f5757570646174654665654d696e44696666526174696ffb3fb999999999999a7066656572617465546f6c6572616e6365bf68726174696f4c6f77fb3f847ae147ae147b69726174696f48696768fb4059000000000000ffff78186d617848746c6356616c7565496e466c696768744d7361741a08f0d180706d6178416363657074656448746c6373181e7165787069727944656c7461426c6f636b73bf6a756e6465726c79696e67190090ff782066756c66696c6c5361666574794265666f726554696d656f7574426c6f636b73bf6a756e6465726c79696e6706ff6b68746c634d696e696d756dbf646d7361741903e8ff73746f52656d6f746544656c6179426c6f636b73bf6a756e6465726c79696e67190090ff756d6178546f4c6f63616c44656c6179426c6f636b73bf6a756e6465726c79696e671903e8ff6e6d696e4465707468426c6f636b73036766656542617365bf646d7361741903e8ff781866656550726f706f7274696f6e616c4d696c6c696f6e74680a7572657365727665546f46756e64696e67526174696ffb3f847ae147ae147b78186d617852657365727665546f46756e64696e67526174696ffb3fa999999999999a78187265766f636174696f6e54696d656f75745365636f6e647314726175746854696d656f75745365636f6e64730a72696e697454696d656f75745365636f6e64730a7370696e67496e74657276616c5365636f6e6473181e7270696e6754696d656f75745365636f6e64730a6e70696e67446973636f6e6e656374f56d6175746f5265636f6e6e656374f47822696e697469616c52616e646f6d5265636f6e6e65637444656c61795365636f6e647305781b6d61785265636f6e6e656374496e74657276616c5365636f6e6473190e1069636861696e486173687840343334393766643766383236393537313038663461333066643963656333616562613739393732303834653930656164303165613333303930303030303030306c6368616e6e656c466c61677301781b7061796d656e74526571756573744578706972795365636f6e6473190e10781d6d756c7469506172745061796d656e744578706972795365636f6e6473183c726d696e46756e64696e675361746f736869731903e8726d617846756e64696e675361746f736869731a00ffffff726d61785061796d656e74417474656d707473056e7472616d706f6c696e654e6f6465bf626964784230333933333838346161663164366231303833393765356566653563383662636632643863613864326637303065646139396462393231346663323731326231333464686f73746e31332e3234382e3232322e31393764706f7274192607ff77656e61626c655472616d706f6c696e655061796d656e74f5ff6c72656d6f74654e6f646549647842303339333338383461616631643662313038333937653565666535633836626366326438636138643266373030656461393964623932313466633237313262313334ff6a63757272656e74546970bf6566697273741a001cf170667365636f6e647900a030303030303032303364613035366231353933366534633564333538373536623239623061393135323638383463613331626662646630663534333838326438303030303030303033626463643836613633326438383431393834313836636635383536386361653237346466393064616238623362656431356333353536303238663230383730306466616430356665316361343531393366623239343263ff7663757272656e744f6e436861696e4665657261746573bf726d757475616c436c6f736546656572617465bf6766656572617465191388ff70636c61696d4d61696e46656572617465bf6766656572617465191388ff6b6661737446656572617465bf67666565726174651930d4ffff6b636f6d6d69746d656e7473bf6e6368616e6e656c56657273696f6ebf646269747378203030303030303030303030303030303030303030303030303030303031313131ff6b6c6f63616c506172616d73bf666e6f6465496478423033356363666162306138653966356364623963656233656466636231643530653561376638656665313838316133373764396431663064363664373933363266306e66756e64696e674b657950617468bf64706174689f010203ffff69647573744c696d697419022278186d617848746c6356616c7565496e466c696768744d7361741a08f0d1806e6368616e6e656c526573657276651902586b68746c634d696e696d756dbf646d7361741903e8ff6b746f53656c6644656c6179bf6a756e6465726c79696e67190090ff706d6178416363657074656448746c6373181e68697346756e646572f4781864656661756c7446696e616c5363726970745075624b6579782c3030313466366135313635376232373633663434643338653663626464376665323332653132643437653437686665617475726573bf696163746976617465649fbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e4f7074696f6e446174614c6f737350726f74656374bfffff67737570706f7274694d616e6461746f7279ffbf67666561747572659f782b66722e6163696e712e65636c6169722e466561747572652e5661726961626c654c656e6774684f6e696f6ebfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782566722e6163696e712e65636c6169722e466561747572652e5061796d656e74536563726574bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e42617369634d756c7469506172745061796d656e74bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f781d66722e6163696e712e65636c6169722e466561747572652e57756d626fbfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782766722e6163696e712e65636c6169722e466561747572652e53746174696352656d6f74654b6579bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782966722e6163696e712e65636c6169722e466561747572652e5472616d706f6c696e655061796d656e74bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782566722e6163696e712e65636c6169722e466561747572652e416e63686f724f757470757473bfffff67737570706f7274684f7074696f6e616cffffffff6c72656d6f7465506172616d73bf666e6f64654964784230333933333838346161663164366231303833393765356566653563383662636632643863613864326637303065646139396462393231346663323731326231333469647573744c696d697419022278186d617848746c6356616c7565496e466c696768744d7361741b00000004a817c8006e6368616e6e656c52657365727665006b68746c634d696e696d756dbf646d73617401ff6b746f53656c6644656c6179bf6a756e6465726c79696e671902d0ff706d6178416363657074656448746c6373181e6d66756e64696e675075624b65797842303335613030663830363537626261313163323630373332313037653061616431306131633461323332656131616464333534313364323561313238323137633437737265766f636174696f6e42617365706f696e747842303366393830653861366136613830356461653134336236633433336139663863383134613138363764303065393533626636663563316237626137383732356265707061796d656e7442617365706f696e7478423033373539333034633361353339346436663465363461343331323130303364643536626366373433626239313731646138393036343166643035343230313365397764656c617965645061796d656e7442617365706f696e7478423033623131363932356233663365613737383935326137636563343834363939663833333032346631356133636663366234336538363739663134363364666363656d68746c6342617365706f696e747842303361363133373464643239363132633732646139663563383566653165343034383230356665333034393039386339323038373037323466643932326131316566686665617475726573bf696163746976617465649fbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e4f7074696f6e446174614c6f737350726f74656374bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782a66722e6163696e712e65636c6169722e466561747572652e496e697469616c526f7574696e6753796e63bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782b66722e6163696e712e65636c6169722e466561747572652e4368616e6e656c52616e676551756572696573bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782b66722e6163696e712e65636c6169722e466561747572652e5661726961626c654c656e6774684f6e696f6ebfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f783366722e6163696e712e65636c6169722e466561747572652e4368616e6e656c52616e676551756572696573457874656e646564bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782766722e6163696e712e65636c6169722e466561747572652e53746174696352656d6f74654b6579bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782566722e6163696e712e65636c6169722e466561747572652e5061796d656e74536563726574bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f782d66722e6163696e712e65636c6169722e466561747572652e42617369634d756c7469506172745061796d656e74bfffff67737570706f7274684f7074696f6e616cffbf67666561747572659f781d66722e6163696e712e65636c6169722e466561747572652e57756d626fbfffff67737570706f7274684f7074696f6e616cffffffff6c6368616e6e656c466c616773006b6c6f63616c436f6d6d6974bf65696e646578006473706563bf6568746c63739fff6766656572617465bf67666565726174651902eeff67746f4c6f63616cbf646d7361741a02fa2d30ff68746f52656d6f7465bf646d7361741a06dc4960ffff6e7075626c69736861626c65547873bf68636f6d6d69745478bf65696e707574bf686f7574506f696e7478486366383432353433626636643130393634663664616264666238633232386137343961373238383361343466303163333865346166306237663764346562643930303030303030306574784f7574785662613834303230303030303030303030323230303230343832663964313764343837376530626235633439356231356139633134616637386364383963353662373037646636353064383661353363363438396530326c72656465656d53637269707479008e35323231303335613030663830363537626261313163323630373332313037653061616431306131633461323332656131616464333534313364323561313238323137633437323130333638643664336530386364326131343236333832306265633436616638353731343662376131383139366339653062633038386665663735343336326531316535326165ff62747879037a3032303030303030303030313031636638343235343362663664313039363466366461626466623863323238613734396137323838336134346630316333386534616630623766376434656264393030303030303030303031376334656138303034346130313030303030303030303030303232303032306331376363326335663332303135633135633262346339393335326138633962613062633165386130616231313434396232656361386366353662663765373234613031303030303030303030303030323230303230653633623533366431366637313935303035363530366230303334636633613134343531396636353134393164313737326335633863323636623662663462313165633330303030303030303030303032323030323061386133316631333434313530383734656536323265383262633131633765323034396532343434386338366262353034633137353161616564666638396335626462623031303030303030303030303232303032303338626233643766623865363638373336333637626661383038663663623061626637636130653030626531336661356639336236383566393238623861383930343030343833303435303232313030643434313536613532663838623036393761363835336631336465393530663535613832643936336532346433363831626333663962366134366363633131383032323035353665373034663935373236633464623938643738646235653230656437656236346664363162373238336462646362333233353765626231656135636630303134383330343530323231303062323236626530353737393634666234366263363034353936643964623233633133336363383066663231383535616338303830373335336661613335373230303232303336633739363962336232376366396539326339383965633661643239393931313464356465613163623162383963666334346666336233666630313732336130313437353232313033356130306638303635376262613131633236303733323130376530616164313061316334613233326561316164643335343133643235613132383231376334373231303336386436643365303863643261313432363338323062656334366166383537313436623761313831393663396530626330383866656637353433363265313165353261656230333265303230ff6e68746c63547873416e64536967739fffffff6c72656d6f7465436f6d6d6974bf65696e646578006473706563bf6568746c63739fff6766656572617465bf67666565726174651902eeff67746f4c6f63616cbf646d7361741a06dc4960ff68746f52656d6f7465bf646d7361741a02fa2d30ffff6474786964784062663261656362306337353961666539663332306661383235313637313130363261323364666235326439306366656566353062656663386632306261303765781872656d6f7465506572436f6d6d69746d656e74506f696e747842303263663961323761323832343964373264393834353965643464613138323565653134333730323466633632323734373435353635626436313336346366356365ff6c6c6f63616c4368616e676573bf6870726f706f7365649fff667369676e65649fff6561636b65649fffff6d72656d6f74654368616e676573bf6870726f706f7365649fff6561636b65649fff667369676e65649fffff6f6c6f63616c4e65787448746c634964007072656d6f74654e65787448746c63496400687061796d656e7473bfff7472656d6f74654e657874436f6d6d6974496e666fbf6572696768747842303239623734376439396431623735663137386161663463653636333431613133323637316564383931303939303835643136316662643561643635333237353032ff6b636f6d6d6974496e707574bf686f7574506f696e7478486366383432353433626636643130393634663664616264666238633232386137343961373238383361343466303163333865346166306237663764346562643930303030303030306574784f7574785662613834303230303030303030303030323230303230343832663964313764343837376530626235633439356231356139633134616637386364383963353662373037646636353064383661353363363438396530326c72656465656d53637269707479008e35323231303335613030663830363537626261313163323630373332313037653061616431306131633461323332656131616464333534313364323561313238323137633437323130333638643664336530386364326131343236333832306265633436616638353731343662376131383139366339653062633038386665663735343336326531316535326165ff781a72656d6f7465506572436f6d6d69746d656e7453656372657473bf6b6b6e6f776e486173686573bfffff696368616e6e656c4964784063663834323534336266366431303936346636646162646662386332323861373439613732383833613434663031633338653461663062376637643465626439ff6e73686f72744368616e6e656c4964bf6269641b000000142b090000ff66627572696564f4736368616e6e656c416e6e6f756e63656d656e74f66d6368616e6e656c557064617465bf697369676e6174757265790080356166306565643063663331626663353135653832363433383931643166343233336434633661343535383632356630373432376630646433343936333830663163666539616133643235653664333831613933393233336435333530636332386364313837306663633136356365396630306462363766333937616139356169636861696e486173687840343334393766643766383236393537313038663461333066643963656333616562613739393732303834653930656164303165613333303930303030303030306e73686f72744368616e6e656c4964bf6269641b000000142b090000ff7074696d657374616d705365636f6e64731a5fd0fb8f6c6d657373616765466c616773016c6368616e6e656c466c616773006f636c747645787069727944656c7461bf6a756e6465726c79696e67190090ff6f68746c634d696e696d756d4d736174bf646d73617401ff6b666565426173654d736174bf646d7361741903e8ff781966656550726f706f7274696f6e616c4d696c6c696f6e7468730a6f68746c634d6178696d756d4d736174bf646d7361741a09d67690ffff6d6c6f63616c53687574646f776ef66e72656d6f746553687574646f776ef6ffff"
        val state = ChannelStateWithCommitments.deserialize(Hex.decode(raw))
       assertTrue { state is Normal }
    }

    @Test
    fun `encrypt - decrypt channel state`() {
        val initial = reachNormal().first

        val raw = ChannelStateWithCommitments.serialize(initial)

        val decryptedByteArray = ChannelStateWithCommitments.deserialize(raw)
        assertEquals(initial, decryptedByteArray)
        val decryptedByteVector = ChannelStateWithCommitments.deserialize(raw.toByteVector())
        assertEquals(initial, decryptedByteVector)
        val decryptedHex = ChannelStateWithCommitments.deserialize(Hex.decode(raw.toHexString()))
        assertEquals(initial, decryptedHex)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (success case)`() {
        val (alice, bob) = reachNormal()

        val a = 764_660_000.msat // initial balance alice
        val b = 190_000_000.msat // initial balance bob
        val p = 42_000_000.msat // a->b payment
        val htlcOutputFee = (2 * 860_000).msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (payment_preimage, cmdAdd) = TestsHelper.makeCmdAdd(p, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = ac0.sendAdd(cmdAdd, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(ac1.availableBalanceForSend(), a - p - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = bc0.receiveAdd(add).right!!
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac2, commit1) = ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac2.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - p - htlcOutputFee)

        val ac3 = ac2.receiveRevocation(revocation1).right!!.first
        assertEquals(ac3.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = bc2.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac4, revocation2) = ac3.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac4.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = bc3.receiveRevocation(revocation2).right!!.first
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - p - htlcOutputFee)

        val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
        val (bc5, fulfill) = bc4.sendFulfill(cmdFulfill).right!!
        assertEquals(bc5.availableBalanceForSend(), b + p) // as soon as we have the fulfill, the balance increases
        assertEquals(bc5.availableBalanceForReceive(), a - p - htlcOutputFee)

        val ac5 = ac4.receiveFulfill(fulfill).right!!.first
        assertEquals(ac5.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac5.availableBalanceForReceive(), b + p)

        val (bc6, commit3) = bc5.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc6.availableBalanceForSend(), b + p)
        assertEquals(bc6.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac6, revocation3) = ac5.receiveCommit(commit3, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac6.availableBalanceForSend(), a - p)
        assertEquals(ac6.availableBalanceForReceive(), b + p)

        val bc7 = bc6.receiveRevocation(revocation3).right!!.first
        assertEquals(bc7.availableBalanceForSend(), b + p)
        assertEquals(bc7.availableBalanceForReceive(), a - p)

        val (ac7, commit4) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac7.availableBalanceForSend(), a - p)
        assertEquals(ac7.availableBalanceForReceive(), b + p)

        val (bc8, revocation4) = bc7.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc8.availableBalanceForSend(), b + p)
        assertEquals(bc8.availableBalanceForReceive(), a - p)

        val ac8 = ac7.receiveRevocation(revocation4).right!!.first
        assertEquals(ac8.availableBalanceForSend(), a - p)
        assertEquals(ac8.availableBalanceForReceive(), b + p)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (failure case)`() {
        val (alice, bob) = reachNormal()

        val a = 764_660_000.msat // initial balance alice
        val b = 190_000_000.msat // initial balance bob
        val p = 42_000_000.msat // a->b payment
        val htlcOutputFee = (2 * 860_000).msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(p, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = ac0.sendAdd(cmdAdd, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(ac1.availableBalanceForSend(), a - p - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = bc0.receiveAdd(add).right!!
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac2, commit1) = ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac2.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - p - htlcOutputFee)

        val ac3 = ac2.receiveRevocation(revocation1).right!!.first
        assertEquals(ac3.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = bc2.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac4, revocation2) = ac3.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac4.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = bc3.receiveRevocation(revocation2).right!!.first
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - p - htlcOutputFee)

        val cmdFail = CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(p, 42)))
        val (bc5, fail) = bc4.sendFail(cmdFail, bob.staticParams.nodeParams.nodePrivateKey).right!!
        assertEquals(bc5.availableBalanceForSend(), b)
        assertEquals(bc5.availableBalanceForReceive(), a - p - htlcOutputFee)

        val ac5 = ac4.receiveFail(fail).right!!.first
        assertEquals(ac5.availableBalanceForSend(), a - p - htlcOutputFee)
        assertEquals(ac5.availableBalanceForReceive(), b)

        val (bc6, commit3) = bc5.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc6.availableBalanceForSend(), b)
        assertEquals(bc6.availableBalanceForReceive(), a - p - htlcOutputFee)

        val (ac6, revocation3) = ac5.receiveCommit(commit3, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac6.availableBalanceForSend(), a)
        assertEquals(ac6.availableBalanceForReceive(), b)

        val bc7 = bc6.receiveRevocation(revocation3).right!!.first
        assertEquals(bc7.availableBalanceForSend(), b)
        assertEquals(bc7.availableBalanceForReceive(), a)

        val (ac7, commit4) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac7.availableBalanceForSend(), a)
        assertEquals(ac7.availableBalanceForReceive(), b)

        val (bc8, revocation4) = bc7.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc8.availableBalanceForSend(), b)
        assertEquals(bc8.availableBalanceForReceive(), a)

        val ac8 = ac7.receiveRevocation(revocation4).right!!.first
        assertEquals(ac8.availableBalanceForSend(), a)
        assertEquals(ac8.availableBalanceForReceive(), b)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (multiple htlcs)`() {
        val (alice, bob) = reachNormal()

        val a = 764_660_000.msat // initial balance alice
        val b = 190_000_000.msat // initial balance bob
        val p1 = 18_000_000.msat // a->b payment
        val p2 = 20_000_000.msat // a->b payment
        val p3 = 40_000_000.msat // b->a payment
        val ac0 = alice.commitments
        val bc0 = bob.commitments
        val htlcOutputFee = (2 * 860_000).msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

        assertTrue(ac0.availableBalanceForSend() > p1 + p2) // alice can afford the payment
        assertTrue(bc0.availableBalanceForSend() > p3) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L

        val (payment_preimage1, cmdAdd1) = TestsHelper.makeCmdAdd(p1, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add1) = ac0.sendAdd(cmdAdd1, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(ac1.availableBalanceForSend(), a - p1 - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val (_, cmdAdd2) = TestsHelper.makeCmdAdd(p2, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac2, add2) = ac1.sendAdd(cmdAdd2, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(ac2.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (payment_preimage3, cmdAdd3) = TestsHelper.makeCmdAdd(p3, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (bc1, add3) = bc0.sendAdd(cmdAdd3, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(bc1.availableBalanceForSend(), b - p3) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(bc1.availableBalanceForReceive(), a)

        val bc2 = bc1.receiveAdd(add1).right!!
        assertEquals(bc2.availableBalanceForSend(), b - p3)
        assertEquals(bc2.availableBalanceForReceive(), a - p1 - htlcOutputFee)

        val bc3 = bc2.receiveAdd(add2).right!!
        assertEquals(bc3.availableBalanceForSend(), b - p3)
        assertEquals(bc3.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)

        val ac3 = ac2.receiveAdd(add3).right!!
        assertEquals(ac3.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)
        assertEquals(ac3.availableBalanceForReceive(), b - p3)

        val (ac4, commit1) = ac3.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac4.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)
        assertEquals(ac4.availableBalanceForReceive(), b - p3)

        val (bc4, revocation1) = bc3.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc4.availableBalanceForSend(), b - p3)
        assertEquals(bc4.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)

        val ac5 = ac4.receiveRevocation(revocation1).right!!.first
        assertEquals(ac5.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)
        assertEquals(ac5.availableBalanceForReceive(), b - p3)

        val (bc5, commit2) = bc4.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc5.availableBalanceForSend(), b - p3)
        assertEquals(bc5.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee)

        val (ac6, revocation2) = ac5.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac6.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee) // alice has acknowledged b's hltc so it needs to pay the fee for it
        assertEquals(ac6.availableBalanceForReceive(), b - p3)

        val bc6 = bc5.receiveRevocation(revocation2).right!!.first
        assertEquals(bc6.availableBalanceForSend(), b - p3)
        assertEquals(bc6.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

        val (ac7, commit3) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac7.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)
        assertEquals(ac7.availableBalanceForReceive(), b - p3)

        val (bc7, revocation3) = bc6.receiveCommit(commit3, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc7.availableBalanceForSend(), b - p3)
        assertEquals(bc7.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

        val ac8 = ac7.receiveRevocation(revocation3).right!!.first
        assertEquals(ac8.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)
        assertEquals(ac8.availableBalanceForReceive(), b - p3)

        val cmdFulfill1 = CMD_FULFILL_HTLC(0, payment_preimage1)
        val (bc8, fulfill1) = bc7.sendFulfill(cmdFulfill1).right!!
        assertEquals(bc8.availableBalanceForSend(), b + p1 - p3) // as soon as we have the fulfill, the balance increases
        assertEquals(bc8.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

        val cmdFail2 = CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(p2, 42)))
        val (bc9, fail2) = bc8.sendFail(cmdFail2, bob.staticParams.nodeParams.nodePrivateKey).right!!
        assertEquals(bc9.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc9.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee) // a's balance won't return to previous before she acknowledges the fail

        val cmdFulfill3 = CMD_FULFILL_HTLC(0, payment_preimage3)
        val (ac9, fulfill3) = ac8.sendFulfill(cmdFulfill3).right!!
        assertEquals(ac9.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
        assertEquals(ac9.availableBalanceForReceive(), b - p3)

        val ac10 = ac9.receiveFulfill(fulfill1).right!!.first
        assertEquals(ac10.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
        assertEquals(ac10.availableBalanceForReceive(), b + p1 - p3)

        val ac11 = ac10.receiveFail(fail2).right!!.first
        assertEquals(ac11.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
        assertEquals(ac11.availableBalanceForReceive(), b + p1 - p3)

        val bc10 = bc9.receiveFulfill(fulfill3).right!!.first
        assertEquals(bc10.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc10.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3) // the fee for p3 disappears

        val (ac12, commit4) = ac11.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac12.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
        assertEquals(ac12.availableBalanceForReceive(), b + p1 - p3)

        val (bc11, revocation4) = bc10.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc11.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc11.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)

        val ac13 = ac12.receiveRevocation(revocation4).right!!.first
        assertEquals(ac13.availableBalanceForSend(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
        assertEquals(ac13.availableBalanceForReceive(), b + p1 - p3)

        val (bc12, commit5) = bc11.sendCommit(bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc12.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc12.availableBalanceForReceive(), a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)

        val (ac14, revocation5) = ac13.receiveCommit(commit5, alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac14.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac14.availableBalanceForReceive(), b + p1 - p3)

        val bc13 = bc12.receiveRevocation(revocation5).right!!.first
        assertEquals(bc13.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc13.availableBalanceForReceive(), a - p1 + p3)

        val (ac15, commit6) = ac14.sendCommit(alice.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(ac15.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac15.availableBalanceForReceive(), b + p1 - p3)

        val (bc14, revocation6) = bc13.receiveCommit(commit6, bob.staticParams.nodeParams.keyManager, logger).right!!
        assertEquals(bc14.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc14.availableBalanceForReceive(), a - p1 + p3)

        val ac16 = ac15.receiveRevocation(revocation6).right!!.first
        assertEquals(ac16.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac16.availableBalanceForReceive(), b + p1 - p3)
    }

    // See https://github.com/lightningnetwork/lightning-rfc/issues/728
    @Test
    fun `funder keeps additional reserve to avoid channel being stuck`() {
        val isFunder = true
        val currentBlockHeight = 144L
        val c = makeCommitments(100000000.msat, 50000000.msat, FeeratePerKw(2500.sat), 546.sat, isFunder)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(c.availableBalanceForSend(), randomKey().publicKey(), currentBlockHeight)
        val (c1, _) = c.sendAdd(cmdAdd, UUID.randomUUID(), currentBlockHeight).right!!
        assertEquals(c1.availableBalanceForSend(), 0.msat)

        // We should be able to handle a fee increase.
        val (c2, _) = c1.sendFee(CMD_UPDATE_FEE(FeeratePerKw(3000.sat))).right!!

        // Now we shouldn't be able to send until we receive enough to handle the updated commit tx fee (even trimmed HTLCs shouldn't be sent).
        val (_, cmdAdd1) = TestsHelper.makeCmdAdd(100.msat, randomKey().publicKey(), currentBlockHeight)
        val e = c2.sendAdd(cmdAdd1, UUID.randomUUID(), currentBlockHeight).left
        assertTrue(e is InsufficientFunds)
    }

    @Test
    fun `can send availableForSend`() {
        val currentBlockHeight = 144L
        listOf(true, false).forEach {
            val c = makeCommitments(702000000.msat, 52000000.msat, FeeratePerKw(2679.sat), 546.sat, it)
            val (_, cmdAdd) = TestsHelper.makeCmdAdd(c.availableBalanceForSend(), randomKey().publicKey(), currentBlockHeight)
            val result = c.sendAdd(cmdAdd, UUID.randomUUID(), currentBlockHeight)
            assertTrue(result.isRight)
        }
    }

    @Test
    fun `can receive availableForReceive`() {
        val currentBlockHeight = 144L
        listOf(true, false).forEach {
            val c = makeCommitments(31000000.msat, 702000000.msat, FeeratePerKw(2679.sat), 546.sat, it)
            val add = UpdateAddHtlc(
                randomBytes32(), c.remoteNextHtlcId, c.availableBalanceForReceive(), randomBytes32(), CltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket
            )
            val result = c.receiveAdd(add)
            assertTrue(result.isRight)
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    companion object {
        fun makeCommitments(toLocal: MilliSatoshi, toRemote: MilliSatoshi, feeRatePerKw: FeeratePerKw = FeeratePerKw(0.sat), dustLimit: Satoshi = 0.sat, isFunder: Boolean = true, announceChannel: Boolean = true): Commitments {
            val localParams = LocalParams(
                randomKey().publicKey(), KeyPath("42"), dustLimit, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, isFunder, ByteVector.empty, Features.empty
            )
            val remoteParams = RemoteParams(
                randomKey().publicKey(), dustLimit, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50,
                randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(),
                Features.empty
            )
            val commitmentInput = Helpers.Funding.makeFundingInputInfo(
                randomBytes32(),
                0, (toLocal + toRemote).truncateToSatoshi(), randomKey().publicKey(), remoteParams.fundingPubKey
            )
            val localCommitTx = Transactions.TransactionWithInputInfo.CommitTx(commitmentInput, Transaction(2, listOf(), listOf(), 0))
            return Commitments(
                ChannelVersion.STANDARD,
                localParams,
                remoteParams,
                channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
                LocalCommit(0, CommitmentSpec(setOf(), feeRatePerKw, toLocal, toRemote), PublishableTxs(localCommitTx, listOf())),
                RemoteCommit(0, CommitmentSpec(setOf(), feeRatePerKw, toRemote, toLocal), randomBytes32(), randomKey().publicKey()),
                LocalChanges(listOf(), listOf(), listOf()),
                RemoteChanges(listOf(), listOf(), listOf()),
                localNextHtlcId = 1,
                remoteNextHtlcId = 1,
                payments = mapOf(),
                remoteNextCommitInfo = Either.Right(randomKey().publicKey()),
                commitInput = commitmentInput,
                remotePerCommitmentSecrets = ShaChain.init,
                channelId = randomBytes32()
            )
        }

        fun makeCommitments(toLocal: MilliSatoshi, toRemote: MilliSatoshi, localNodeId: PublicKey, remoteNodeId: PublicKey, announceChannel: Boolean): Commitments {
            val localParams = LocalParams(
                localNodeId, KeyPath("42L"), 0.sat, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, isFunder = true, ByteVector.empty, Features.empty
            )
            val remoteParams = RemoteParams(
                remoteNodeId, 0.sat, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), Features.empty
            )
            val commitmentInput = Helpers.Funding.makeFundingInputInfo(
                randomBytes32(), 0, (toLocal + toRemote).truncateToSatoshi(), randomKey().publicKey(), remoteParams.fundingPubKey
            )
            val localCommitTx = Transactions.TransactionWithInputInfo.CommitTx(commitmentInput, Transaction(2, listOf(), listOf(), 0))
            return Commitments(
                ChannelVersion.STANDARD,
                localParams,
                remoteParams,
                channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
                LocalCommit(0, CommitmentSpec(setOf(), FeeratePerKw(0.sat), toLocal, toRemote), PublishableTxs(localCommitTx, listOf())),
                RemoteCommit(0, CommitmentSpec(setOf(), FeeratePerKw(0.sat), toRemote, toLocal), randomBytes32(), randomKey().publicKey()),
                LocalChanges(listOf(), listOf(), listOf()),
                RemoteChanges(listOf(), listOf(), listOf()),
                localNextHtlcId = 1,
                remoteNextHtlcId = 1,
                payments = mapOf(),
                remoteNextCommitInfo = Either.Right(randomKey().publicKey()),
                commitInput = commitmentInput,
                remotePerCommitmentSecrets = ShaChain.init,
                channelId = randomBytes32()
            )
        }
    }
}