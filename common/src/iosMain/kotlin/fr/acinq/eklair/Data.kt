package fr.acinq.eklair

import platform.UIKit.UIDevice

import fr.acinq.eklair.crypto.Sha256

data class EklairUser(val id: String)

data class MessageContainer(val identity: EklairUser, val message: String, val counter: Int)
