//
//  NodeManager.swift
//  Eklair
//
//  Created by Thomas CARTON on 03/04/2020.
//

import Foundation

import eklair

public class NodeManager {

    let queue = DispatchQueue(label: "actorQueue", qos: .userInitiated)

    var host: String = ""
    let user: EklairUser

    var counter = 0

    // MARK: - Life cycle

    init() {
        self.user = EklairUser(id: UUID().uuidString)
    }

    // MARK: - Methods

    func hash(message: String) -> String {
        let hash = CommonKt.hash(value: message)
        // LoggerKt.log(level: .INFO(), tag: "NodeManager", message: "Hash '\(message)' > \(hash)")

        return hash
    }

    func connect(_ completion: @escaping (() -> Void)) {
        // LoggerKt.log(level: .INFO(), tag: "NodeManager", message: "Connect()")

        self.host = self.user.id

        queue.async {

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
