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
    @State var passphrase: String = ""

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

                TextField("Pass phrase", text: $passphrase)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 290)
                    .padding(.all, 8.0)
                    .cornerRadius(8)
                    .border(Color.black, width: 1)

                Spacer()

                Button(action: { self.didPressRenew() }) {
                    Text("Renew")
                        .bold()
                        .foregroundColor(.white)
                        .frame(width: 312, height: 42)
                        .background(Color.gray)
                }
                .cornerRadius(8)
                .padding(.bottom, 100)
            }
            .navigationBarTitle("Wallet recovery phrase", displayMode: .inline)
        }.onAppear {
            self.updateWordsList(renew: false)
        }.onTapGesture {
            self.endEditing()
        }
    }
}

// MARK: - Actions

extension WordslistView {

    private func endEditing() {
        let keyWindow = UIApplication.shared.windows.first { $0.isKeyWindow }
        keyWindow?.endEditing(true)
    }

    func didPressRenew() {
        updateWordsList(renew: true)
    }

}

// MARK: - Helper

extension WordslistView {

    func updateWordsList(renew: Bool = false) {
        eklairManager.getWordsList(completion: { (list) in
            self.wordslist = list
        }, renew)
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
