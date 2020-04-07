/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenix.main


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.*
import fr.acinq.eklair.EklairAPI
import fr.acinq.eklair.Hex
import fr.acinq.eklair.LightningSession
import fr.acinq.eklair.SocketBuilder
import fr.acinq.eklair.crypto.Sha256
import fr.acinq.phoenix.BaseFragment
import fr.acinq.phoenix.databinding.FragmentMainBinding
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MainFragment : BaseFragment() {
  override val log: Logger = LoggerFactory.getLogger(this::class.java)
  private lateinit var mBinding: FragmentMainBinding
  private lateinit var model: MainViewModel

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    mBinding = FragmentMainBinding.inflate(inflater, container, false)
    mBinding.lifecycleOwner = this
    return mBinding.root
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    model = ViewModelProvider(this).get(MainViewModel::class.java)
    mBinding.model = model
  }

  @ExperimentalStdlibApi
  override fun onStart() {
    super.onStart()
//    mBinding.socketInput.setText("13.248.222.197:9735")
    mBinding.socketInput.setText("51.77.223.203:19735")
    mBinding.encodeButton.setOnClickListener { encodeSomething() }
    mBinding.socketButton.setOnClickListener {
      try {
        val (host, port) = mBinding.socketInput.text.toString().split(":")
        model.startSocket(host, port.toInt())
      } catch (e: Exception) {
        log.error("failed to read for input: ${mBinding.socketInput.text.toString()}: ", e)
        Toast.makeText(context, "could not read address: ${e.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun encodeSomething() {
    lifecycleScope.launch(CoroutineExceptionHandler { _, exception ->
      log.error("error in fire and wait ", exception)
    }) {
      val input = mBinding.encodeInput.text.toString()
      val res = model.encodeSomething(input)
      log.info("encoded $input -> $res")
      model.encodedValue.value = res
    }
  }
}

class MainViewModel : ViewModel() {
  val log: Logger = LoggerFactory.getLogger(this::class.java)
  val encodedValue = MutableLiveData<String>()
  val socketLogs = MutableLiveData<String>()

  init {
    encodedValue.value = "..."
    socketLogs.value = null
  }

  suspend fun encodeSomething(s: String): String {
    return coroutineScope {
      async(Dispatchers.Default) {
        Hex.encode(Sha256.hash(s.toByteArray(Charsets.UTF_8)))
      }
    }.await()
  }

  @ExperimentalStdlibApi
  fun startSocket(host: String, port: Int) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        val id = "02413957815d05abb7fc6d885622d5cdc5b7714db1478cb05813a8474179b83c5c"
        val priv = ByteArray(32) { 0x01.toByte() }
        val pub = Secp256k1.computePublicKey(priv)
        val keyPair: Pair<ByteArray, ByteArray> = Pair(pub, priv)
        val nodeId = Hex.decode(id)
        logSocket("using node_id=$id")

          log.info("running socket coroutine")
          logSocket("building socket...")
          val socketHandler = SocketBuilder.buildSocketHandler(host, port)
          logSocket("connected to peer")
          log.info("got socket handler")
          val (enc, dec, ck) = EklairAPI.handshake(keyPair, nodeId, socketHandler)
          logSocket("handshake ok")
          val session = LightningSession(socketHandler, enc, dec, ck)
          val ping = Hex.decode("0012000a0004deadbeef")
          val init = Hex.decode("001000000002a8a0")
          session.send(init)
          log.info("init socket $init")
          logSocket("init socket $init")
          while (true) {
            val received = session.receive()
            val pong = Hex.encode(received)
            log.info("received pong=$pong from socket peer")
            logSocket("<- pong: $ping")

            delay(2000)

            log.info("sending ping=$ping")
            logSocket("-> ping: $ping ->")
            session.send(ping)
          }
      }
    }
  }

  private fun logSocket(line: String) {
    socketLogs.postValue(socketLogs.value?.run { "$this\n$line" } ?: line)
  }
}