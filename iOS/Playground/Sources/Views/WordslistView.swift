//
//  WordslistView.swift
//  Playground
//
//  Copyright © 2020 Acinq. All rights reserved.
//

import Foundation
import SwiftUI

import eklair

struct WordslistView: View {

    var nodeManager: NodeManager
    @State var wordslist: [String]?

    var body: some View {

        NavigationView {
            VStack {
                if wordslist != nil {
                    HStack {
                        VStack {
                            ForEach(0..<6) { index in
                                HStack {
                                    Text("#\(index)")
                                    Spacer()
                                    Text(self.wordslist![index])
                                        .font(.system(size: 20))
                                        .bold()
                                }
                                .frame(width: 120, height: 30, alignment: .leading)
                            }
                        }
                        .padding()

                        VStack {
                            ForEach(6..<12) { index in
                                HStack {
                                    Text("#\(index)")
                                    Spacer()
                                    Text(self.wordslist![index])
                                        .font(.system(size: 20))
                                        .bold()
                                }
                                .frame(width: 120, height: 30, alignment: .leading)
                            }
                        }
                        .padding()
                    }
                } else {
                    Text("...")
                }

                Spacer()

                Button(action: { self.didPressRenew() }) {
                    Text("Renew")
                        .bold()
                        .foregroundColor(.white)
                        .frame(width: 312, height: 42)
                        .background(Color.gray)
                }
                .cornerRadius(8)
                .padding()
            }
        }
    }
}

extension WordslistView {

    func didPressRenew() {
        wordslist = SeedKt.generateWordsList(entropy: nil)
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
