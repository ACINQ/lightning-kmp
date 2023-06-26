# Funds recovery

The following steps lets you recover on-chain funds managed by `lightning-kmp`.

## Closed channels

When channels are closed, funds are sent to an address derived from your seed using BIP39 and BIP84.
You can use any on-chain wallet that supports these two standards to recover those funds.

For example, when using [electrum](https://electrum.org/):

- Create a new standard wallet
- Select "I already have a seed"
- Enter your 12 words, click on "Options" and check "BIP39 seed"
- Select "native segwit (p2wpkh)"
- Wait for the funds to show up

## Pending swap-in transactions

When swapping funds to a `lightning-kmp` wallet, the following steps are performed:

- funds are sent to a swap-in address via a swap transaction
- we wait for that transaction to have enough confirmations
- then, if the fees don't exceed the user's liquidity policy, these funds are moved into a lightning channel

The swap transaction's output can be spent using either:

1. A signature from the user's wallet and a signature from the remote node
2. A signature from the user's wallet after a refund delay

Funds can be recovered using the second option and [Bitcoin Core](https://github.com/bitcoin/bitcoin).
This process needs at least Bitcoin Core 25.0.

This process will become simpler once popular on-chain wallets (such as [electrum](https://electrum.org/)) add supports for output script descriptors.

### Extract master keys

We don't directly export your extended master private key for security reasons, so you will need to manually insert it in the descriptor.
You can obtain your extended master private key in [electrum](https://electrum.org/). After restoring your seed, type `wallet.keystore.xprv` in the console to obtain your master `xprv`.

### Create recovery wallet

Create a wallet to recover your funds using the following command:

```sh
bitcoin-cli createwallet recovery
```

### Import descriptor into the recovery wallet

`lightning-kmp` provides the public descriptor for your swap-in address, which uses the following template:

```txt
wsh(and_v(v:pk([<master_fingerprint>/<derivation_path>]<extended_public_key>),or_d(pk(<swap_server_public_key>),older(<refund_delay>))))
```

For example, it will look like this:

```txt
wsh(and_v(v:pk([14620948/51h/0h/0h]tpubDCvYeHUZisCMV3h1zPevPWQmNPfA3g3vnu7gDqskXVCbJB1VKk2F7LApV6TTdm1sCyGout8ma27CCHvYTuMZxpwrcHnLwL4kaXW8z2KfFcW),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))
```

Replace the `extended_public_key` and the `derivation_path` with the extended private key obtained in the [first step](#extract-master-keys).
In our example, the extended private key matching our seed is `tprv8ZgxMBicQKsPdKRFLVct6VDpfmCxk6aC7iAF8tb6roQ7hv1zFCyGwDLBUUxMVJ95dTiQS5VvCbQ6J7CcGqguw5SbnDpNjbjpfVwcMwUtmjS`, so we create the following private descriptor:

```txt
wsh(and_v(v:pk(tprv8ZgxMBicQKsPdKRFLVct6VDpfmCxk6aC7iAF8tb6roQ7hv1zFCyGwDLBUUxMVJ95dTiQS5VvCbQ6J7CcGqguw5SbnDpNjbjpfVwcMwUtmjS/51h/0h/0h),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))
```

We need to obtain a checksum for this descriptor, which is provided by Bitcoin Core:

```sh
bitcoin-cli getdescriptorinfo "wsh(and_v(v:pk(tprv8ZgxMBicQKsPdKRFLVct6VDpfmCxk6aC7iAF8tb6roQ7hv1zFCyGwDLBUUxMVJ95dTiQS5VvCbQ6J7CcGqguw5SbnDpNjbjpfVwcMwUtmjS/51h/0h/0h),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))"

{
  "descriptor": "wsh(and_v(v:pk(tpubD6NzVbkrYhZ4WnT3E9HUVtswEnituRm6h1m2RQdQH5CWYQGksbns7hx3ediWHpFEkEQC4vPssnQN2gQpzkodRDuMA7nQtWiQ5EDzkGpGVNw/51'/0'/0'),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))#m8v4e6vu",
  "checksum": "dlcgkrnc",
  "isrange": false,
  "issolvable": true,
  "hasprivatekeys": true
}
```

We can the append this checksum to our private descriptor and import it into our recovery wallet:

```sh
bitcoin-cli -rpcwallet=recovery importdescriptors '[{ "desc": "wsh(and_v(v:pk(tprv8ZgxMBicQKsPdKRFLVct6VDpfmCxk6aC7iAF8tb6roQ7hv1zFCyGwDLBUUxMVJ95dTiQS5VvCbQ6J7CcGqguw5SbnDpNjbjpfVwcMwUtmjS/51h/0h/0h),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))#dlcgkrnc", "timestamp": 0 }]'

[
  {
    "success": true,
    "warnings": [
      "Not all private keys provided. Some wallet functionality may return unexpected errors"
    ]
  }
]
```

Bitcoin Core will then scan the blockchain to find funds that were sent to a matching address.
This is a slow process, which can be sped up by setting the `timestamp` field to a value slightly before the first usage of `lightning-kmp`.

Once Bitcoin Core is done with the scanning process, the `getwalletinfo` command will return `"scanning": false`:

```sh
bitcoin-cli -rpcwallet=recovery getwalletinfo

{
  "walletname": "recovery",
  "walletversion": 169900,
  "format": "sqlite",
  "balance": 1.50000000,
  "unconfirmed_balance": 0.00000000,
  "immature_balance": 0.00000000,
  "txcount": 1,
  "keypoolsize": 4000,
  "keypoolsize_hd_internal": 4000,
  "paytxfee": 0.00000000,
  "private_keys_enabled": true,
  "avoid_reuse": false,
  "scanning": false,
  "descriptors": true,
  "external_signer": false
}
```

You can then find available funds matching the descriptor we imported:

```sh
bitcoin-cli -rpcwallet=recovery listtransactions

[
  {
    "address": "bcrt1qw78cdcsn55vwsvmwe9qgwnx0fwffzqej7keuqfjnwj5xm0f5u6js2hp66f",
    "parent_descs": [
      "wsh(and_v(v:pk(tpubD6NzVbkrYhZ4WnT3E9HUVtswEnituRm6h1m2RQdQH5CWYQGksbns7hx3ediWHpFEkEQC4vPssnQN2gQpzkodRDuMA7nQtWiQ5EDzkGpGVNw/51'/0'/0'),or_d(pk(0256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fd),older(25920))))#m8v4e6vu"
    ],
    "category": "receive",
    "amount": 1.50000000,
    "label": "",
    "vout": 1,
    "confirmations": 5,
    "blockhash": "6e1048a8d7829d36a766188b499ddcc2e497193427678d115fd341b2b452c0bd",
    "blockheight": 151,
    "blockindex": 1,
    "blocktime": 1687759025,
    "txid": "d9940b7eb709ff8eaec307bdd6d20633e30a6eb1627d9ef8c8e03dfd28298c75",
    "wtxid": "261492e5f930b82f65f269bb3006db9c3ef14423e5f52f2a185ace18704bb7b0",
    "walletconflicts": [
    ],
    "time": 1687759025,
    "timereceived": 1687759181,
    "bip125-replaceable": "no"
  }
]
```

### Send funds to a different address

Once those funds have been recovered and the refund delay has expired (the `confirmations` field of the previous command exceeds `25920`), you can send them to your normal on-chain wallet.
Compute the total amount received (in our example, 1.5 BTC), choose the address to send to (for example, `bcrt1q9ez7rt33wynwpah582lnqlj3u0tpzsrkj2flas`) and create a transaction using all of the received funds:

```sh
bitcoin-cli -rpcwallet=recovery walletcreatefundedpsbt '[{"txid":"d9940b7eb709ff8eaec307bdd6d20633e30a6eb1627d9ef8c8e03dfd28298c75","vout":1,"sequence":25920}]' '[{"bcrt1q9ez7rt33wynwpah582lnqlj3u0tpzsrkj2flas":1.5}]' 0 '{"subtractFeeFromOutputs":[0]}'

{
  "psbt": "cHNidP8BAFICAAAAAXWMKSj9PeDI+J59YrFuCuMzBtLWvQfDro7/Cbd+C5TZAQAAAABAZQAAAQzI8AgAAAAAFgAULkXhrjFxJuD29Dq/MH5R49YRQHYAAAAAAAEAiQIAAAABsDXoV21bcbM8ii+Nyo4r8ZWmMEJIiaYqYg6pKaXiOiMAAAAAAP3///8CnBMVIQEAAAAiUSA520UqAgN8jz9APGIBbNHksiweuAEnMZvjgpMKiRUKkoDR8AgAAAAAIgAgd4+G4hOlGOgzbslAh0zPS5KRAzL1s8AmU3Sobb005qWWAAAAAQErgNHwCAAAAAAiACB3j4biE6UY6DNuyUCHTM9LkpEDMvWzwCZTdKhtvTTmpQEFTSED1ZiIPcIwgcWSbaso29B11ULE+6VERxkh27lqMde8SRmtIQJW6UgYDzPwZyRnEKQWVghPwkW5ftoIHv4eSIshV31g/axzZAJAZbJoIgYCVulIGA8z8GckZxCkFlYIT8JFuX7aCB7+HkiLIVd9YP0EsxuziiIGA9WYiD3CMIHFkm2rKNvQddVCxPulREcZIdu5ajHXvEkZEBRiCUgzAACAAAAAgAAAAIAAAA==",
  "fee": 0.00002420,
  "changepos": -1
}

bitcoin-cli -rpcwallet=recovery walletprocesspsbt "cHNidP8BAFICAAAAAXWMKSj9PeDI+J59YrFuCuMzBtLWvQfDro7/Cbd+C5TZAQAAAABAZQAAAQzI8AgAAAAAFgAULkXhrjFxJuD29Dq/MH5R49YRQHYAAAAAAAEAiQIAAAABsDXoV21bcbM8ii+Nyo4r8ZWmMEJIiaYqYg6pKaXiOiMAAAAAAP3///8CnBMVIQEAAAAiUSA520UqAgN8jz9APGIBbNHksiweuAEnMZvjgpMKiRUKkoDR8AgAAAAAIgAgd4+G4hOlGOgzbslAh0zPS5KRAzL1s8AmU3Sobb005qWWAAAAAQErgNHwCAAAAAAiACB3j4biE6UY6DNuyUCHTM9LkpEDMvWzwCZTdKhtvTTmpQEFTSED1ZiIPcIwgcWSbaso29B11ULE+6VERxkh27lqMde8SRmtIQJW6UgYDzPwZyRnEKQWVghPwkW5ftoIHv4eSIshV31g/axzZAJAZbJoIgYCVulIGA8z8GckZxCkFlYIT8JFuX7aCB7+HkiLIVd9YP0EsxuziiIGA9WYiD3CMIHFkm2rKNvQddVCxPulREcZIdu5ajHXvEkZEBRiCUgzAACAAAAAgAAAAIAAAA=="

{
  "psbt": "cHNidP8BAFICAAAAAXWMKSj9PeDI+J59YrFuCuMzBtLWvQfDro7/Cbd+C5TZAQAAAABAZQAAAQzI8AgAAAAAFgAULkXhrjFxJuD29Dq/MH5R49YRQHYAAAAAAAEAiQIAAAABsDXoV21bcbM8ii+Nyo4r8ZWmMEJIiaYqYg6pKaXiOiMAAAAAAP3///8CnBMVIQEAAAAiUSA520UqAgN8jz9APGIBbNHksiweuAEnMZvjgpMKiRUKkoDR8AgAAAAAIgAgd4+G4hOlGOgzbslAh0zPS5KRAzL1s8AmU3Sobb005qWWAAAAAQErgNHwCAAAAAAiACB3j4biE6UY6DNuyUCHTM9LkpEDMvWzwCZTdKhtvTTmpQEImAMARzBEAiBNe5Y/fGWNfCIh2oBoZZHh5Em1kR3GFumpa0bgn9WRCQIgTDKGl/F59wpGRhdJ/jLlOTHqszmHonQTD4qgVNNJIc4BTSED1ZiIPcIwgcWSbaso29B11ULE+6VERxkh27lqMde8SRmtIQJW6UgYDzPwZyRnEKQWVghPwkW5ftoIHv4eSIshV31g/axzZAJAZbJoAAA=",
  "complete": true
}

bitcoin-cli -rpcwallet=recovery finalizepsbt "cHNidP8BAFICAAAAAXWMKSj9PeDI+J59YrFuCuMzBtLWvQfDro7/Cbd+C5TZAQAAAABAZQAAAQzI8AgAAAAAFgAULkXhrjFxJuD29Dq/MH5R49YRQHYAAAAAAAEAiQIAAAABsDXoV21bcbM8ii+Nyo4r8ZWmMEJIiaYqYg6pKaXiOiMAAAAAAP3///8CnBMVIQEAAAAiUSA520UqAgN8jz9APGIBbNHksiweuAEnMZvjgpMKiRUKkoDR8AgAAAAAIgAgd4+G4hOlGOgzbslAh0zPS5KRAzL1s8AmU3Sobb005qWWAAAAAQErgNHwCAAAAAAiACB3j4biE6UY6DNuyUCHTM9LkpEDMvWzwCZTdKhtvTTmpQEImAMARzBEAiBNe5Y/fGWNfCIh2oBoZZHh5Em1kR3GFumpa0bgn9WRCQIgTDKGl/F59wpGRhdJ/jLlOTHqszmHonQTD4qgVNNJIc4BTSED1ZiIPcIwgcWSbaso29B11ULE+6VERxkh27lqMde8SRmtIQJW6UgYDzPwZyRnEKQWVghPwkW5ftoIHv4eSIshV31g/axzZAJAZbJoAAA="

{
  "hex": "02000000000101758c2928fd3de0c8f89e7d62b16e0ae33306d2d6bd07c3ae8eff09b77e0b94d9010000000040650000010cc8f008000000001600142e45e1ae317126e0f6f43abf307e51e3d6114076030047304402204d7b963f7c658d7c2221da80686591e1e449b5911dc616e9a96b46e09fd5910902204c328697f179f70a46461749fe32e53931eab33987a274130f8aa054d34921ce014d2103d598883dc23081c5926dab28dbd075d542c4fba544471921dbb96a31d7bc4919ad210256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fdac7364024065b26800000000",
  "complete": true
}

bitcoin-cli -rpcwallet=recovery sendrawtransaction 02000000000101758c2928fd3de0c8f89e7d62b16e0ae33306d2d6bd07c3ae8eff09b77e0b94d9010000000040650000010cc8f008000000001600142e45e1ae317126e0f6f43abf307e51e3d6114076030047304402204d7b963f7c658d7c2221da80686591e1e449b5911dc616e9a96b46e09fd5910902204c328697f179f70a46461749fe32e53931eab33987a274130f8aa054d34921ce014d2103d598883dc23081c5926dab28dbd075d542c4fba544471921dbb96a31d7bc4919ad210256e948180f33f067246710a41656084fc245b97eda081efe1e488b21577d60fdac7364024065b26800000000
```

Wait for that transaction to confirm, and your funds will have been successfully recovered!
