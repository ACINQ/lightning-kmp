//
//  SocketView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct SocketView: View {

    var nodeManager: NodeManager

    @State var status: String = "Not connected"

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                Text("\(status)").lineLimit(nil).padding(.all)

                Button(action: { self.didPressConnect() }){
                    Text("Connect")
                        .bold()
                        .foregroundColor(.white)
                        .frame(width: 102, height: 42)
                        .background(Color.gray)
                }
                .cornerRadius(8)
                .padding(.top, 30)

                Spacer()
            }
            .padding(.top, 40)
            .navigationBarTitle("Socket", displayMode: .inline)
        }
    }
}

extension SocketView {

    func didPressConnect() {

        self.nodeManager.connect {
            self.status = "Connected to \n\(self.nodeManager.host)"
        }
    }
}

// MARK: - Previews

struct SocketView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            SocketView(nodeManager: NodeManager())
                .previewDevice("iPhone SE")
        }
    }
}
