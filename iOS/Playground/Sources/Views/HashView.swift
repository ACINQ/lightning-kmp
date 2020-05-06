//
//  HashView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct HashView: View {
    @State var nodeManager: NodeManager = NodeManager()

    @State var message: String = ""
    @State var hashedMessage: String = "..."

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                TextField("Message to hash", text: $message)
                    .frame(maxWidth: 290)
                    .padding(.all, 8.0)
                    .border(Color.black, width: 1)
                    .multilineTextAlignment(.center)

                Button(action: { self.didPressHash() }){
                    Text("Hash")
                        .bold()
                        .foregroundColor(.white)
                        .frame(width: 312, height: 42)
                        .background(Color.gray)
                }
                .cornerRadius(8)

                Text("Hashed value:")
                    .frame(width: 300, height: 30)
                    .padding(.top)
                Text("\(hashedMessage)")
                    .frame(width: 310, height: 60)
                    .multilineTextAlignment(.leading)

                Spacer()
                Button(action: { self.didPressLog() }){
                    Text("Log")
                        .bold()
                        .foregroundColor(.white)
                        .frame(width: 312, height: 42)
                        .background(Color.gray)
                }
                .cornerRadius(8)

                Spacer(minLength: 40)
            }
            .padding(.top, 40)
            .navigationBarTitle("Hash", displayMode: .inline)
        }
    }
}

extension HashView {

    func didPressHash() {
        hashedMessage = nodeManager.hash(message: message)

        let keyWindow = UIApplication.shared.windows.first { $0.isKeyWindow }
        keyWindow?.endEditing(true)
    }

    func didPressLog() {
        nodeManager.testLog()
    }
}

// MARK: - Previews

struct HashView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            HashView()
                .previewDevice("iPhone SE")
        }
    }
}
