//
//  InOutView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct InOutView: View {
    @State var nodeManager: NodeManager = NodeManager()

    @State private var platformCount = 0
    @State private var sharedCount = 0
    @State private var pauseDuration: Float = 1

    @State private var startStopMode = false

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                HStack(alignment: .center) {
                    Text("iOS #: ")
                        .font(.largeTitle)
                        .foregroundColor(.gray)
                    Spacer()
                    Text(String(platformCount))
                        .font(.largeTitle)
                }
                .frame(width: 280, alignment: .center)

                HStack(alignment: .center) {
                    Text("KN #: ")
                        .font(.largeTitle)
                        .foregroundColor(.gray)
                    Spacer()
                    Text(String(sharedCount))
                        .font(.largeTitle)
                }
                .frame(width: 280, alignment: .center)

                HStack(alignment: .center){
                    VStack {
                        Text("Pause (\(pauseDuration, specifier: "%.2f")s)")
                        Slider(value: $pauseDuration,
                               in: 0.1...10,
                               onEditingChanged: self.onPauseChanged
                        ).padding(16)
                    }
                }
                .frame(width: 200, height: 250, alignment: .center)

                HStack(alignment: .top) {
                    Button(action: { self.didPressStart() }) {
                        VStack {
                            Image(systemName: "timer")
                                .font(.system(size: 70))
                                .padding(.bottom, 8)
                            Text("Start")
                        }
                        .foregroundColor(self.startStopMode ? .gray : .green)
                    }
                    .padding()
                    .disabled(startStopMode)

                    Button(action: { self.didPressStop() }) {
                        VStack {
                            Image(systemName: "nosign")
                                .font(.system(size: 70))
                                .padding(.bottom, 7)
                            Text("Stop")
                        }
                        .foregroundColor(!self.startStopMode ? .gray : .red)

                    }
                    .padding()
                    .disabled(!startStopMode)
                }
                .frame(width: 200, height: 50, alignment: .center)
                
                Spacer()
            }
            .frame(height: 600, alignment: .top)
        }
        .navigationBarTitle("In Out", displayMode: .inline)
    }
}

// MARK: - Actions

extension InOutView {
    func didPressStart() {
        print("Start!")
        self.startStopMode = true

        nodeManager.startInOut(closure: {
            DispatchQueue.main.async {
                self.platformCount += 1
            }
        }){ cnt in
            DispatchQueue.main.async {
                self.sharedCount = cnt
            }
        }
    }

    func didPressStop() {
        print("Stop!")
        self.startStopMode = false

        DispatchQueue.main.async {
            self.nodeManager.stopInOut()
        }
    }
    
    func onPauseChanged(_: Bool){
        print(">>> \(self.pauseDuration)")
        DispatchQueue.main.async {
            self.nodeManager.updatePause(self.pauseDuration)
        }
    }
}

// MARK: - Previews

struct InOutView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            InOutView()
                .previewDevice("iPhone SE")
        }
    }
}
