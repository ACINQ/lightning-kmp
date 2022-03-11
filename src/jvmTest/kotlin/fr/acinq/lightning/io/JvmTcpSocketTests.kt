package fr.acinq.lightning.io

import fr.acinq.lightning.tests.utils.LightningTestSuite
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals


class JvmTcpSocketTests : LightningTestSuite() {

    @Test
    fun `build public key from base64`() {
        val testCases = listOf(
            // testnet.qtornado.com
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwkLgqNkkTbwpV3gMdgDA+jJFdzrOp8vIDT/qxVIox8NZ53pxPc2N44aeY1NJx4TyfpHUGcI7l+gxZfLr8a13o3CIQSotDbZZdJhS6Ir5tT4iRqwZJch+HayTQf9rztv8OQWgrflWDzCiYtBA5PGx6LEQWyah/xPPUbeANe/ndEzlfAhXjNcynSfrkikzTgFNBqnc5CcTkHjYgzCXqMwyZCD6kQTQG+eqIHSHul21dwUougfCWCR+P0zFA7LeUfPz2mLZktmGXjqTyYZ+0ZTUgJz/MMZt9PDWGJZsHQzoFSCMicukKtnvZ4Q0gbPOoYp8+WjD4SH+WmC3MZdLagsi05hUDdm7PHIM1VHQTALLGRnW3yTOaqhsvYGAM5UOkDcmgUqIr6IztHGWCKldfbhSc4l7BIgvwW2M6FxYlSAcavIodNfvEC1ythdMzl8bZsBjGIOZ39WtiM0grgcg7bb8W5ovZpLOXpzZBjS0zB0sZJnumjS+3jCSjy9rZXGUn3JmMdqtTV8RQxkB8OBJhFf5qtMSZXiJIr9RH71VoJKjnds/hoILHuCKU3HOJeo0+4KSD8+q4g3tZLr/haIrsHg5uifT9db6tDML1PTKpbHkW+f3w9PdhSmsNUUXrgNmQ0MoBhxV7U2Qcug3jX3xaf1PgwWDg3nZZizhuvBceY0IYLECAwEAAQ==",
            // tn.not.fyi
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA7/z3CDZ2d6gJ/7mEt9yI9KNKxs5dbwzhKB99uYdWp1QogMHEo9Jg+uYGg2M7UhO76vOFaf1Hp2gstd1Q0n2GLwtq3a0JSam3EIaC7QuX8V190IexyPSVdwaPtPBzN4KCZd8smfiR01oMJkhCH2MKaCrJKL7PJrSZMmrLMzo5yYTnHtBZVR9gL0T5bJeO3NTNuQBV1ft6kYaKa30d5zMO7j/CkzclQkh6d07RrS/YnkZNswfnqKdEqNNGa3FfuZ8vwSwRtpEQRAdkjxSFsLSoIv+HoTx1nFhbmaqdZasFIxmn9Zp30KMb328k5j9fszcAn78y7tVodiddODBMgGSpofq+nJDkNlKD0NVNLML50IB2U2BL/62+7c2RguvLYljNkTSB3HhF+b6DiGLZDbg48ea12Hr+9Nxf4lW+hIKO3s/88MMlrB2lORORiuFtmiqYiiTc5YzJPXzXGhoCCmgQxij6XYi0v4bpFr8wANtDobZhziWZSHm6T+u5RsSSkMGHc6hJaZmVg9xGznsD7RmRa+Wmf/Tl4sY0hI+nea4YyrN7+i1KT04EA9HrG6nBUKgKJkp2OciKmGGrINmdSCvkZ9laCxcGpEuAL0sI/WalmAXDTm/rjNdMZ0AClD3gCweqsblKZ07Ewtzh0DE65B7vtZWgWZM/wz4dnMYwY9VIrcUCAwEAAQ==",
            // testnet.aranguren.org
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA+RL/AH7wn08YKlRCswERM0JBdGycRuGBgQbM0guzDxi7Ov01WB9AM0DX7GC09pAOefbRP8QzXEhyKO0qpnin+5Vz4jKS+xv4zPGx2MpTqjJxzom/6v13cumZxWXMzVSeNUTjVp2sOPQ1JQaHqqjs2lTgShWu+pAgKUH1KPWxMSz21cI+AQkT8NuuXe0USYYIeiXzyTpciIaBf50j6185u+4bUwA3hvdPZyrkJDtSluJ0HiJzCSFlmNYNHLqbvZNAYrgUM3qJRTsvmD0JK6mm8m7iXW4m6mKX22VgR93meD/3rdcrJ8FbMbVlkS3wimzcYezls9JytaXupyeRKhQjTQIDAQAB",
            // electrum.emzy.de
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAufqDv0nJICJPoP86wOPYM/XIfFs6vmVrGEeBZmy9MmMNubulhnyE+sWuFhnIX+0uuFlJ18LJYFOIg0fAaFdN5xh4vagBNXP9dCcxfVfMGfv9xBQZfWhKrDjC4DCJ82n3K+Q4RqpK/yS9GIZIcqrG3rxELBZ8NHVPIXveW0PnagGeQ31NDdOAq9MBKiKRcmfKem5daUEo/xM4nTt+tOTxl5sHicdQSCePHXewMXzmkM/Vw1rMJZKeTwnLX44TsppEi47fXUFcduB2+A1xHQIgE9wa4Bqc2ZoUtKKBayeeU02C2SBFgVxtAWT6YESdcPP8u+pR7lADA7QZNUVNKMvMNwIDAQAB",
        )

        testCases.forEach { keyBase64 ->
            val key = JvmTcpSocket.buildPublicKey(Base64.getDecoder().decode(keyBase64))
            assertEquals(keyBase64, String(Base64.getEncoder().encode(key.encoded)))
        }
    }
}