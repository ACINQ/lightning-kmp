//
//  WordslistView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

struct WordslistView: View {

    var nodeManager: NodeManager

    var body: some View {

        NavigationView {
            VStack(alignment: .center) {
                Text("fbsdjtg")
//                Text("\(status)").lineLimit(nil).padding(.all)
//
//                Button(action: { self.didPressConnectChannel() }){
//                    Text("Connect")
//                        .bold()
//                        .foregroundColor(.white)
//                        .frame(width: 102, height: 42)
//                        .background(Color.gray)
//                }
//                .cornerRadius(8)
//                .padding(.top, 30)
//
//                Spacer()
            }
            .padding(.top, 40)
            .navigationBarTitle("Wordslist", displayMode: .inline)
        }
    }
}

// MARK: - Previews

struct WordslistView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            WordslistView(nodeManager: NodeManager())
                .previewDevice("iPhone SE")
        }
    }
}
