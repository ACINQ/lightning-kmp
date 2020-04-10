//
//  InOutView.swift
//  Eklair
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct InOutView: View {

    @State private var inCount = 0
    @State private var outCount = 0

    @State private var startStopMode = false

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                HStack(alignment: .center) {
                    Text("#IN: ")
                        .font(.largeTitle)
                        .foregroundColor(.gray)
                    Spacer()
                    Text(String(inCount))
                        .font(.largeTitle)
                }
                .frame(width: 200, alignment: .center)

                HStack(alignment: .center) {
                    Text("#OUT: ")
                        .font(.largeTitle)
                        .foregroundColor(.gray)
                    Spacer()
                    Text(String(outCount))
                        .font(.largeTitle)
                }
                .frame(width: 200, alignment: .center)

                Spacer()

                HStack(alignment: .top) {
                    Button(action: {
                        print("Start!")
                        self.startStopMode = true
                    }) {
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

                    Button(action: {
                        print("Stop!")
                        self.startStopMode = false
                    }) {
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
            }
            .frame(height: 200, alignment: .top)
            .frame(height: 500, alignment: .top)
        }
        .navigationBarTitle("In Out", displayMode: .inline)
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
