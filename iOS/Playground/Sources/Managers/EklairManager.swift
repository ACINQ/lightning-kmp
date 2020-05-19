//
//  EklairManager.swift
//  Eklair
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation

import eklair

public class EklairManager {
    let logger: Logger

    let eklairQueue = DispatchQueue(label: "eklairQueue", qos: .background)
    var item: DispatchWorkItem?

    let actorQueue = DispatchQueue(label: "actorQueue", qos: .userInitiated)

    let eklair = Eklair()

    var host: String = ""
    let user: EklairUser

    var counter = 0
    private var pause = 5
    
    let workQueue = DispatchQueue(label: "workQueue", qos: .background, attributes: .concurrent)
    let workGroup = DispatchGroup()
    var isGroupActive = false

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
        if isGroupActive {
            workGroup.leave()
        }
    }

    func startInOut(closure: @escaping () -> Void, closureOut: @escaping (Int) -> Void) {
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
        isGroupActive = true
        workGroup.wait()

        if pause == -1 {
            return "STOP"
        }

        return String(pause)
    }
    
    func closureOut(out: String) -> String {
        return out
    }

    func stopInOut() {
        // magic count to stop the world for the POC
        // (catched on the KN side)
        pause = -1

        workGroup.leave()
        item?.cancel()
    }

    func connect(_ completion: @escaping (() -> Void)) {
        // LoggerKt.log(level: .INFO(), tag: "EklairManager", message: "Connect()")

        self.host = self.user.id

        actorQueue.async {

            DispatchQueue.main.async {
                completion()
            }
        }
    }

    func connectWithChannel(){
        eklairQueue.async {
            self.eklair.runChannel()
        }
    }

    // MARK: - Words List

    func loadWordsList() -> [String]? {
        let defaults = UserDefaults.standard

        let wordsList = defaults.object(forKey: "wordsList") as? [String] ?? [String]()
        return wordsList
    }

    func saveWordsList(wordsList: [String]) {
        let defaults = UserDefaults.standard

        defaults.set(wordsList, forKey: "wordsList")
    }

    func getWordsList(completion: @escaping ([String]) -> Void, _ renew: Bool = false) {
        var wordsList = loadWordsList()
        if renew {
            wordsList = [String]()
        }

        if wordsList?.isEmpty == true {
            SeedKt.runWordsListGeneration(entropy: nil) { (list) in
                self.saveWordsList(wordsList: list)

                completion(list)
            }
        }
    }
}
