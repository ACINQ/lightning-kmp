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

- funds are sent to a swap-in address via a swap transaction. 
- we wait for that transaction to have enough confirmations
- then, if the fees don't exceed the user's liquidity policy, these funds are moved into a lightning channel

We use musig2 to aggregate user keys (user being the wallet) and server keys (server being the LSP: the ACINQ node): swap-in addresses are standard p2tr addresses, and
swap-in transactions to your wallet are indistinguishable from other p2tr transactions.

The swap transaction's output can be spent using either:

1. A aggregated musig2 signature built from a partial signature from the user's wallet and a partial signature from the remote node
2. A signature from the user's wallet after a refund delay

Funds can be recovered using the second option and [Bitcoin Core](https://github.com/bitcoin/bitcoin).
This process needs at least Bitcoin Core 26.0.

This process will become simpler once popular on-chain wallets (such as [electrum](https://electrum.org/)) add supports for output script descriptors.

### Get your wallet descriptor

lighting-kmp provides both a public descriptor and private descriptor for your swap-in wallet.
The public descriptor can be used to create a watch-only wallet for your swap-in funds.
The private descriptor can be used to recover your swap-in funds, after the refund delay has passed.
:warning: Do not share this private descriptor with anyone ! 

### Create recovery wallet

Create a wallet to recover your funds using the following command:

```sh
bitcoin-cli createwallet recovery
```

### Import descriptor into the recovery wallet

`lightning-kmp` provides a public and private descriptor for your swap-in wallet, which both use the following template:

```txt
tr(<extended_public_key>,and_v(v:pk(<master_key>/<derivation_path>),older(<refund_delay>)))
```

For example, your public descriptor will look like this:

```txt
tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(tprv8h9x3k1njDX6to9q2G3aEvcic81MJk64SUVMXFc2Eo2YQqPGCBpQa8uJDkTz3DMHVXEmvhuwf4ShjLQ7YaVr34x9DFT3y43cPzVKGB94r1n/*),older(25920)))#7dne06j5
```

And your private descriptor will look like this: 

```
tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(tpubDDqzCA42sbCmnGBcuuiAeLGqB9XHU5Gy1n68omeKf4pwFKe2padzkdXAPsDMWMdee879oPYrGrTS8sioqyjv8b6TztunE526eo4Au9kTef3/*),older(25920)))#z6mq2a3u
```

We can import our private descriptor into our recovery wallet:

```sh
bitcoin-cli -rpcwallet=recovery importdescriptors '[{ "desc": "tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(tprv8ZgxMBicQKsPdKRFLVct6VDpfmCxk6aC7iAF8tb6roQ7hv1zFCyGwDLBUUxMVJ95dTiQS5VvCbQ6J7CcGqguw5SbnDpNjbjpfVwcMwUtmjS/51h/0h/0h/*),older(25920)))#rn7cy7yr", "timestamp": 0 }]'

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
    "address": "bcrt1pzz7rudhpqyy6zdnuwrg3dpnethckfzncma2urxghuc62dz49zenqv0p0q6",
    "parent_descs": [
      "tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(tpubDDqzCA42sbCmnGBcuuiAeLGqB9XHU5Gy1n68omeKf4pwFKe2padzkdXAPsDMWMdee879oPYrGrTS8sioqyjv8b6TztunE526eo4Au9kTef3/*),older(144)))#zqam8e56"
    ],
    "category": "receive",
    "amount": 0.10000000,
    "vout": 0,
    "abandoned": false,
    "confirmations": 1,
    "blockhash": "06361beb06e7d24bea80fc6800f4b5f374f09542a07fae77a7f8c26a9f7544b2",
    "blockheight": 146,
    "blockindex": 1,
    "blocktime": 1700670588,
    "txid": "4c3236b1fa1f3ed124ab83b1667be95f855952e68729eae54a9f511c8c8cb993",
    "wtxid": "16ab0b31f680e5bd4f149527148b542e16de96ce2d14db9c41552752f3d8e655",
    "walletconflicts": [
    ],
    "time": 1700670571,
    "timereceived": 1700670571,
    "bip125-replaceable": "no"
  }
]
```

### Send funds to a different address

Once those funds have been recovered and the refund delay has expired (the `confirmations` field of the previous command exceeds `25920`), you can send them to your normal on-chain wallet.
Compute the total amount received (in our example, 1.5 BTC), choose the address to send to (for example, `bcrt1q9ez7rt33wynwpah582lnqlj3u0tpzsrkj2flas`) and create a transaction using all of the received funds:

```sh
bitcoin-cli -rpcwallet=recovery walletcreatefundedpsbt '[{"txid":"4c3236b1fa1f3ed124ab83b1667be95f855952e68729eae54a9f511c8c8cb993", "vout":0, "sequence":144}]' '[{"bcrt1qzy4h8dux6pjl8ys979632uynqffd53vjkzffjl":0.09}]'
{
  "psbt": "cHNidP8BAHECAAAAAZO5jIwcUZ9K5eoph+ZSWYVf6XtmsYOrJNE+H/qxNjJMAAAAAACQAAAAAkBUiQAAAAAAFgAUEStzt4bQZfOSBfF1FXCTAlLaRZIEOA8AAAAAABYAFEx1yJgBL6kfpf2sybIL0WajM0rXAAAAAAABASuAlpgAAAAAACJRIBC8PjbhAQmhNnxw0RaGeV3xZIp431XBmRfmNKaKpRZmIhXBH8VZ2clsWVOJXTFQ5k6/PdaWoLCOdYZQtI/2JR1+YNEnIG0cAcgg4JAO3Y5ZtLOD5zp/WFAHJFWAT5z/6Z+k+FQbrQKQALLAIRYfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0QUA0EkObyEWbRwByCDgkA7djlm0s4PnOn9YUAckVYBPnP/pn6T4VBspAWR9Jdbf5zHI25Gs69RTMJILBCLUX82cmJj59Bk4SZKgTHUGgQAAAAABFyAfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0QEYIGR9Jdbf5zHI25Gs69RTMJILBCLUX82cmJj59Bk4SZKgAAAiAgMhzD3XSvW4p+oRyBAvB6rUHaOCIyjVxJV9tEin3sUiqxjpcZ0vVAAAgAEAAIAAAACAAQAAAAEAAAAA",
  "fee": 0.00002620,
  "changepos": 1
}

bitcoin-cli -rpcwallet=recovery walletprocesspsbt "cHNidP8BAHECAAAAAZO5jIwcUZ9K5eoph+ZSWYVf6XtmsYOrJNE+H/qxNjJMAAAAAACQAAAAAkBUiQAAAAAAFgAUEStzt4bQZfOSBfF1FXCTAlLaRZIEOA8AAAAAABYAFEx1yJgBL6kfpf2sybIL0WajM0rXAAAAAAABASuAlpgAAAAAACJRIBC8PjbhAQmhNnxw0RaGeV3xZIp431XBmRfmNKaKpRZmIhXBH8VZ2clsWVOJXTFQ5k6/PdaWoLCOdYZQtI/2JR1+YNEnIG0cAcgg4JAO3Y5ZtLOD5zp/WFAHJFWAT5z/6Z+k+FQbrQKQALLAIRYfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0QUA0EkObyEWbRwByCDgkA7djlm0s4PnOn9YUAckVYBPnP/pn6T4VBspAWR9Jdbf5zHI25Gs69RTMJILBCLUX82cmJj59Bk4SZKgTHUGgQAAAAABFyAfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0QEYIGR9Jdbf5zHI25Gs69RTMJILBCLUX82cmJj59Bk4SZKgAAAiAgMhzD3XSvW4p+oRyBAvB6rUHaOCIyjVxJV9tEin3sUiqxjpcZ0vVAAAgAEAAIAAAACAAQAAAAEAAAAA"
{
  "psbt": "cHNidP8BAHECAAAAAZO5jIwcUZ9K5eoph+ZSWYVf6XtmsYOrJNE+H/qxNjJMAAAAAACQAAAAAkBUiQAAAAAAFgAUEStzt4bQZfOSBfF1FXCTAlLaRZIEOA8AAAAAABYAFEx1yJgBL6kfpf2sybIL0WajM0rXAAAAAAABASuAlpgAAAAAACJRIBC8PjbhAQmhNnxw0RaGeV3xZIp431XBmRfmNKaKpRZmAQiLA0D59zl6TLlwXk2oCio3Ffff8dpRQmpYWs7MaY+cUk1Zfl03hzxj1vwIAHBQQbyh33PCX7JoDrlXxlo/Le86jMjQJiBtHAHIIOCQDt2OWbSzg+c6f1hQByRVgE+c/+mfpPhUG60CkACyIcEfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0QAAIgIDIcw910r1uKfqEcgQLweq1B2jgiMo1cSVfbRIp97FIqsY6XGdL1QAAIABAACAAAAAgAEAAAABAAAAAA==",
  "complete": true,
  "hex": "0200000000010193b98c8c1c519f4ae5ea2987e65259855fe97b66b183ab24d13e1ffab136324c000000000090000000024054890000000000160014112b73b786d065f39205f1751570930252da459204380f00000000001600144c75c898012fa91fa5fdacc9b20bd166a3334ad70340f9f7397a4cb9705e4da80a2a3715f7dff1da51426a585acecc698f9c524d597e5d37873c63d6fc0800705041bca1df73c25fb2680eb957c65a3f2def3a8cc8d026206d1c01c820e0900edd8e59b4b383e73a7f5850072455804f9cffe99fa4f8541bad029000b221c11fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d100000000"
}

bitcoin-cli sendrawtransaction 0200000000010193b98c8c1c519f4ae5ea2987e65259855fe97b66b183ab24d13e1ffab136324c000000000090000000024054890000000000160014112b73b786d065f39205f1751570930252da459204380f00000000001600144c75c898012fa91fa5fdacc9b20bd166a3334ad70340f9f7397a4cb9705e4da80a2a3715f7dff1da51426a585acecc698f9c524d597e5d37873c63d6fc0800705041bca1df73c25fb2680eb957c65a3f2def3a8cc8d026206d1c01c820e0900edd8e59b4b383e73a7f5850072455804f9cffe99fa4f8541bad029000b221c11fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d100000000
09efe025805b2db8ae845a94639e5ad415756fb0d010aad54bf3f74ae71e015d
```

Wait for that transaction to confirm, and your funds will have been successfully recovered!
