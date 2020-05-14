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

    var eklairManager: EklairManager
    @State var wordslist: [String]?

    var body: some View {

        NavigationView {
            VStack {
                Spacer()

                if wordslist?.isEmpty == false {
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
            .navigationBarTitle("Wallet recovery phrase", displayMode: .inline)
        }.onAppear {
            self.wordslist = self.eklairManager.getWordsList()
        }
    }
}

extension WordslistView {

    func didPressRenew() {
        self.wordslist = self.eklairManager.getWordsList(true)
    }

}

// MARK: - Previews

struct WordslistView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            WordslistView(eklairManager: EklairManager())
                .previewDevice("iPhone SE")
        }
    }
}
