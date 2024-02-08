# Funds recovery

:warning: to recover swap-in funds sent to older versions of Phoenix (up to and including version 2.1.2) please refer to [this guide](https://github.com/ACINQ/lightning-kmp/blob/v1.5.15/RECOVERY.md)

The following steps let you recover on-chain funds managed by `lightning-kmp`.

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

1. An aggregated musig2 signature built from a partial signature from the user's wallet and a partial signature from the remote node
2. A signature from the user's wallet after a refund delay

Funds can be recovered using the second option and [Bitcoin Core](https://github.com/bitcoin/bitcoin).
This process needs at least Bitcoin Core 26.0.

This process will become simpler once popular on-chain wallets (such as [electrum](https://electrum.org/)) add support for output script descriptors.

### Create recovery wallet

#### Compute your refund master private key

For security reasons, we don't directly export the refund master private key used for swap-ins, so you will need to manually insert it in the descriptor.
You can obtain your extended master private key in [electrum](https://electrum.org/). 

1. Create a new wallet, and choose `Standard wallet`
2. Choose `I already have a seed`
3. Enter your 12-word seed, and in the `Options` menu select `BIP39 seed`
4. In the `Script type and Derivation path` dialog select `legacy(p2pkh)` and override the derivation path with `m/52h/0h/2h/0`
5. In the `Console` tab, enter `wallet.keystore.xprv`. This will give you your refund master private key

#### Create your refund wallet descriptor

Copy the descriptor from the `SWAP_IN WALLET` section in the `Wallet Info` menu on your Phoenix wallet. It should look like this:

```txt
tr(<extended_public_key>,and_v(v:pk(<refund_master_public_key>/<derivation_path>),older(<refund_delay>)))
```

For example:
```txt
tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(xpub6EE2N7jrues5kfjrsyFA5f7hknixqqAEKs8vyMN4QW9vDmYnChzpeBPkBYduBobbe4miQ34xHG4Jpwuq5bHXLZY1xixoGynW31ySUqqVvcU/*),older(2590)))#sv8ug44m
```

You can check that the extended public key in this descriptor matches the extended public key of the wallet you created with Electrum to compute your refund master private key.

Replace the `refund master public key` with your refund master private key. For example:
```txt
tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(xprvA1EfxcCy5HJnYBfPmwi9iXAyCktUSNSNxeDLAxxSrAcwLyDdfAga6P5GLHNdq7EiXe8Pzu6Py6xGwT7UTkw824FYf3v6fbRStvYsWqFTu29/*),older(2590)))#sv8ug44m
```

### Create a bitcoin core recovery wallet

Create a wallet to recover your funds using the following command:

```shell
bitcoin-cli -named createwallet wallet_name=recovery
```

### Import your descriptor into the recovery wallet

We can import our private descriptor into our recovery wallet. Since you replaced your refund master public key with your refund master private key, the descriptor checksum is no longer valid, but bitcoin core will give you the correct checksum:

```shell
bitcoin-cli -rpcwallet=recovery importdescriptors '[{ "desc": "tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(xprvA1EfxcCy5HJnYBfPmwi9iXAyCktUSNSNxeDLAxxSrAcwLyDdfAga6P5GLHNdq7EiXe8Pzu6Py6xGwT7UTkw824FYf3v6fbRStvYsWqFTu29/*),older(2590)))#sv8ug44m", "timestamp":0}]'
[
  {
    "success": false,
    "error": {
      "code": -5,
      "message": "Provided checksum 'sv8ug44m' does not match computed checksum 'ksphr9r4'"
    }
  }
]
```

Update the checksum and try again:
```shell
bitcoin-cli -rpcwallet=recovery importdescriptors '[{ "desc": "tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(xprvA1EfxcCy5HJnYBfPmwi9iXAyCktUSNSNxeDLAxxSrAcwLyDdfAga6P5GLHNdq7EiXe8Pzu6Py6xGwT7UTkw824FYf3v6fbRStvYsWqFTu29/*),older(2590)))#ksphr9r4", "timestamp":0}]'

[
  {
    "success": true,
    "warnings": [
      "Range not given, using default keypool range",
      "Not all private keys provided. Some wallet functionality may return unexpected errors"
    ]
  }
]
```

Bitcoin Core will then scan the blockchain to find funds that were sent to a matching address.
This is a slow process, which can be sped up by setting the `timestamp` field to a value slightly before the first usage of `lightning-kmp`.

Once Bitcoin Core is done with the scanning process, the `getwalletinfo` command will return `"scanning": false`:

```shell
bitcoin-cli -rpcwallet=recovery getwalletinfo

{
  "walletname": "recovery",
  "walletversion": 169900,
  "format": "sqlite",
  "balance": 0.00003000,
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
  "external_signer": false,
  "blank": false,
  "birthtime": 1707742312,
  "lastprocessedblock": {
    "hash": "00000000000000000001760b2e9b05c08275c664d78c1ae59093faa64b57b3b2",
    "height": 830146
  }
}
```

You can then find available funds matching the descriptor we imported:

```shell
 bitcoin-cli -rpcwallet=recovery listtransactions                                                                     
[
  {
    "address": "bc1p6pxx4mp43xkac222jmfy958gpxqn7duku6cka9ahdfmdp9aak74sza58es",
    "parent_descs": [
      "tr(1fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d1,and_v(v:pk(xpub6EE2N7jrues5kfjrsyFA5f7hknixqqAEKs8vyMN4QW9vDmYnChzpeBPkBYduBobbe4miQ34xHG4Jpwuq5bHXLZY1xixoGynW31ySUqqVvcU/*),older(2590)))#sv8ug44m"
    ],
    "category": "receive",
    "amount": 0.00003000,
    "vout": 1,
    "abandoned": false,
    "confirmations": 0,
    "trusted": false,
    "txid": "a9e38fee226e3a598d035afdbecd99c5cb0a6039866cc29fd15d7b27c7d8dcff",
    "wtxid": "701989d4f18951ae757409ea948e4a9bc3de9bf37dd14a4dcd21ba5355df2401",
    "walletconflicts": [
    ],
    "time": 1707745877,
    "timereceived": 1707745877,
    "bip125-replaceable": "yes"
  }
]
```

### Send funds to a different address

Once those funds have been recovered and the refund delay has expired (the `confirmations` field of the previous command exceeds `25920`), you can send them to your normal on-chain wallet.
For now, this process involves selecting the inputs that you want to spend and creating the spending transaction manually, as documented below, but future versions of Bitcoin Core will probably make this easier.

For example, if `listtransactions` lists a UTXO `5e9d2a387572fe0c8a4996c2f34373b3fbbdb19ff106b84fc91c2450eb27cbe7:0` of `0.002` Bitcoin, this is how you would send it to your on-chain address.

```shell
bitcoin-cli -rpcwallet=recovery -named walletcreatefundedpsbt inputs='[{"txid":"5e9d2a387572fe0c8a4996c2f34373b3fbbdb19ff106b84fc91c2450eb27cbe7", "vout":0, "sequence":2590}]' outputs='[{"bcrt1q9qt02fkc2rfpm3w37uvec62kd7yh688uyf8v4w":0.002}]' subtractFeeFromOutputs='[0]'
{
  "psbt": "cHNidP8BAFICAAAAAefLJ+tQJBzJT7gG8Z+xvfuzc0PzwpZJigz+cnU4Kp1eAAAAAAAeCgAAAXAFAwAAAAAAFgAUKBb1JthQ0h3F0fcZnGlWb4l9HPwAAAAAAAEBK0ANAwAAAAAAIlEgRsEcQhkAfS6VDjLeJZ2NJRqKVgaibPLHI6oN28AfRBwiFcAfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0Scg6oyRJt9CuIzhFceFlxIolSJ/PTrrUxnVV4zHUivjR7WtAh4KssAhFh/FWdnJbFlTiV0xUOZOvz3WlqCwjnWGULSP9iUdfmDRBQDQSQ5vIRbqjJEm30K4jOEVx4WXEiiVIn89OutTGdVXjMdSK+NHtSkBorv4wrBqM9DCSo9s2yigvE2CbsIJiMCn0WW9crBila72QOqbAAAAAAEXIB/FWdnJbFlTiV0xUOZOvz3WlqCwjnWGULSP9iUdfmDRARggorv4wrBqM9DCSo9s2yigvE2CbsIJiMCn0WW9crBila4AAA==",
  "fee": 0.00002000,
  "changepos": -1
}
```

```shell
bitcoin-cli -rpcwallet=recovery walletprocesspsbt cHNidP8BAFICAAAAAefLJ+tQJBzJT7gG8Z+xvfuzc0PzwpZJigz+cnU4Kp1eAAAAAAAeCgAAAXAFAwAAAAAAFgAUKBb1JthQ0h3F0fcZnGlWb4l9HPwAAAAAAAEBK0ANAwAAAAAAIlEgRsEcQhkAfS6VDjLeJZ2NJRqKVgaibPLHI6oN28AfRBwiFcAfxVnZyWxZU4ldMVDmTr891pagsI51hlC0j/YlHX5g0Scg6oyRJt9CuIzhFceFlxIolSJ/PTrrUxnVV4zHUivjR7WtAh4KssAhFh/FWdnJbFlTiV0xUOZOvz3WlqCwjnWGULSP9iUdfmDRBQDQSQ5vIRbqjJEm30K4jOEVx4WXEiiVIn89OutTGdVXjMdSK+NHtSkBorv4wrBqM9DCSo9s2yigvE2CbsIJiMCn0WW9crBila72QOqbAAAAAAEXIB/FWdnJbFlTiV0xUOZOvz3WlqCwjnWGULSP9iUdfmDRARggorv4wrBqM9DCSo9s2yigvE2CbsIJiMCn0WW9crBila4AAA==
{
  "psbt": "cHNidP8BAFICAAAAAefLJ+tQJBzJT7gG8Z+xvfuzc0PzwpZJigz+cnU4Kp1eAAAAAAAeCgAAAXAFAwAAAAAAFgAUKBb1JthQ0h3F0fcZnGlWb4l9HPwAAAAAAAEBK0ANAwAAAAAAIlEgRsEcQhkAfS6VDjLeJZ2NJRqKVgaibPLHI6oN28AfRBwBCIsDQEstkcuMh1AB1Nf1XkhBUuFT6WfeWmx+7VWOaUNW1t56AFz7d+QI1v+Xz7dyQTw8YuzvdoWXajAFzyYwluHc2ysmIOqMkSbfQriM4RXHhZcSKJUifz0661MZ1VeMx1Ir40e1rQIeCrIhwB/FWdnJbFlTiV0xUOZOvz3WlqCwjnWGULSP9iUdfmDRAAA=",
  "complete": true,
  "hex": "02000000000101e7cb27eb50241cc94fb806f19fb1bdfbb37343f3c296498a0cfe7275382a9d5e00000000001e0a00000170050300000000001600142816f526d850d21dc5d1f7199c69566f897d1cfc03404b2d91cb8c875001d4d7f55e484152e153e967de5a6c7eed558e694356d6de7a005cfb77e408d6ff97cfb772413c3c62ecef7685976a3005cf263096e1dcdb2b2620ea8c9126df42b88ce115c78597122895227f3d3aeb5319d5578cc7522be347b5ad021e0ab221c01fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d100000000"
}
```
```shell
bitcoin-cli sendrawtransaction 02000000000101e7cb27eb50241cc94fb806f19fb1bdfbb37343f3c296498a0cfe7275382a9d5e00000000001e0a00000170050300000000001600142816f526d850d21dc5d1f7199c69566f897d1cfc03404b2d91cb8c875001d4d7f55e484152e153e967de5a6c7eed558e694356d6de7a005cfb77e408d6ff97cfb772413c3c62ecef7685976a3005cf263096e1dcdb2b2620ea8c9126df42b88ce115c78597122895227f3d3aeb5319d5578cc7522be347b5ad021e0ab221c01fc559d9c96c5953895d3150e64ebf3dd696a0b08e758650b48ff6251d7e60d100000000
16d5a43fe6260b1a5993d97d711cfb4323fb27b44c9d34c547fb1693bf1c8900
```

Wait for that transaction to confirm, and your funds will have been successfully recovered!
