//
//  NodeManager.swift
//  Eklair
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation

import eklair

public class NodeManager {
    let logger: Logger

    let eklairQueue = DispatchQueue(label: "eklairQueue", qos: .background)
    var item: DispatchWorkItem?

    let actorQueue = DispatchQueue(label: "actorQueue", qos: .userInitiated)

    var host: String = ""
    let user: EklairUser

    var counter = 0
    private var pause = 1000
    
    let workQueue = DispatchQueue(label: "workQueue", qos: .background, attributes: .concurrent)
    let workGroup = DispatchGroup()

    // MARK: - Life cycle

    init() {
        self.logger = Logger.init(tag: "Playground")
        self.user = EklairUser(id: UUID().uuidString)
    }

    // MARK: - Methods

    func hash(message: String) -> String {
        let hash = CommonKt.hash(value: message)
        logger.info { "Hash '\(message)' > \(hash)." }

        return hash
    }

    func testLog() {
        logger.info { "This log has been triggered by iOS but is coming from Kotlin using `println`." }
    }
    
    func updatePause(_ pause: Float){
        self.pause = Int(pause * 1000)

//        if workGroup
        workGroup.leave()
    }

    func startInOut(closure: @escaping () -> Void, closureOut: @escaping (Int) -> Void) {
//        eklairQueue.async {
//            while (true) {
//                print("toto")
//                sleep(2)
//            }
//        }
//
//            guard let textBlocks = self?.textBlocks else { return }
//            for textBlock in textBlocks where self?.item?.isCancelled == false {
//                let semaphore = DispatchSemaphore(value: 0)
//                self?.startTalking(string: textBlock) {
//                    semaphore.signal()
//                }
//                semaphore.wait()
//            }
//            self?.item = nil

        item = DispatchWorkItem { [weak self] in
            var counter = 0
            while self?.item?.isCancelled == false {
                print(counter)
                counter += 1

                closure()
                sleep(1)
            }
        }

        eklairQueue.async(execute: item!)
        
        let queueIn = DispatchQueue(label: "queueIn", qos: .background, attributes: .concurrent)
        let queueOut = DispatchQueue(label: "queueOut", qos: .background, attributes: .concurrent)
        let queue = DispatchQueue(label: "app")//, qos: .background, attributes: .concurrent)

        let eklairGroup = DispatchGroup()
        eklairGroup.enter()
        queue.async {
            DispatchersKt.runCoroutineStepping(
                closureStop: { inStr in
                    var result = "AZEAZE"
                    let group = DispatchGroup()
                    group.enter()
                    queueIn.async {
                        result = self.closureToStopCount(in: inStr)
                        group.leave()
                    }
                    group.wait()
                    return result
                },
                
               closureOut: { outStr in
                    var result = ""
                    let group = DispatchGroup()
                    group.enter()
                        queueOut.async {
                            result = self.closureOut(out: outStr)
                            closureOut(Int(result)!)
                            group.leave()
                        }
                    group.wait()
                    return result
                }
            )
            eklairGroup.leave()
        }
        eklairGroup.notify(queue: .main) {
            self.stopInOut()
        }
    }

    func closureToStopCount(in _: String) -> String{
        workGroup.enter()
        workGroup.wait()

        if pause == -1 {
            return "STOP"
        }

        return String(pause)
    }
    
    func closureOut(out: String) -> String{
     return out
    }
    func stopInOut() {
//        eklairQueue.stop
        pause = -1 // magic count to stop the world for the POC

        workGroup.leave()
        item?.cancel()
    }

    func connect(_ completion: @escaping (() -> Void)) {
        // LoggerKt.log(level: .INFO(), tag: "NodeManager", message: "Connect()")

        self.host = self.user.id

        actorQueue.async {

            DispatchQueue.main.async {

//                self.host = self.user.id

                completion()
            }

//            self.logger.withActor { msg in
//                if let response = msg as? HostResponseMessage{
//                    self.host = "Connected to \(response.response)"
//                    return EklairObjects().none
//                }
//                print("Entering group")
//                self.group.enter()
//                self.msg = msg
//                self.group.wait(wallTimeout: .now() + 0.1)
//
//                let response = self.nextMsg ?? EklairObjects().none
//                self.nextMsg = nil
//                return response
//            }
        }
    }

    // "02413957815d05abb7fc6d885622d5cdc5b7714db1478cb05813a8474179b83c5c@51.77.223.203:19735"
    func startSocket(nodeId: String, host: String, port: String) {

    }
}


//    queue.async {
//        self.logger.test()
//    }
//    logger.nativeLog {
//        NSLog("Swifty logging")
//        return "Swift string \(type(of: self))"
//    }
//    counter += 1


//    let queue = DispatchQueue(label: "actorQueue", qos: .userInitiated)
//    let group = DispatchGroup()

//    @State var counter = 0
//    @State var msg: EklairActorMessage?
//    @State var nextMsg : Any?


//    func withActor(){
//        queue.async {
//            self.logger.withActor { msg in
//                if let response = msg as? HostResponseMessage{
//                    self.host = "Connected to \(response.response)"
//
//                    return EklairObjects().none
//                }
//
//                print("Entering group")
//                self.group.enter()
//                self.msg = msg
//                self.group.wait() // (wallTimeout: .now() + 0.1)
//
//                let response = self.nextMsg ?? EklairObjects().none
//                self.nextMsg = nil
//                return response
//            }
//        }
//    }
//
//    func next() {
//        if !self.host.contains("@") {
//            nextMsg = EklairObjects().hostMsg
//        }else{
//            counter += 1
//            if counter >= 30 {
//                nextMsg = EklairObjects().disconnect
//            }else{
//                nextMsg = EklairObjects().ping
//            }
//        }
//        self.group.leave()
//    }
//
//
//    /*
//     Unused for the time being, to launch eclair using a basic channel impl
//     */
//    func channel(){
//        queue.async {
//            self.logger.channel()
//        }
//    }
//
//    /*
//     Unused for the time being, "old" boot launch
//     */
//    func test() {
//        let queue = DispatchQueue.init(label: "Eklair", qos: .background)
//        queue.async {
//            EklairApp.init().run()
//        }
//    }
//}


//            Button(action: withActor){
//                Text("Run Eklair [Actor]")
//                    .foregroundColor(.white)
//                    .bold()
//            }
//            .frame(width: 300, height: 42)
//            .background(Color.blue)
//            .cornerRadius(16)
//
//            Button(action: next){
//                Text("Next action")
//                    .foregroundColor(.white)
//                    .bold()
//            }
//            .frame(width: 300, height: 42)
//            .background(Color.blue)
//            .cornerRadius(16)
//
//            Spacer()
//
//            VStack(alignment: .center) {
//                TextField("Message", text: $message)
//                    .padding(.all, 8.0)
//                    .border(Color.black, width: 1)
//                    .multilineTextAlignment(.center)
//                }.padding().frame(width: 300)
//
//            Button(action: { self.hashMe() }){
//                Text("Hash Message")
//                    .foregroundColor(.white)
//                    .bold()
//            }
//            .frame(width: 300, height: 42)
//            .background(Color.blue)
//            .cornerRadius(16)
//
//            Text("Hashed value \(hashedMessage)").frame(width: 300, height: 150)
//        }
//    }
//}


class Toto: KotlinSuspendFunction1{
    
}
