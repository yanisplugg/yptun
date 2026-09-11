package org.olcbox.app.vpn.ssh

import com.jcraft.jsch.JSch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An Ed25519 key must be usable for publickey auth through the Bouncy Castle implementations the
 * installers pin JSch to — on Android they were missing entirely, so the key was never offered.
 */
class SshEd25519KeyTest {

    @Test
    fun ed25519KeyLoadsAndSignsViaBouncyCastle() {
        useBouncyCastleAlgorithms
        assertEquals("com.jcraft.jsch.bc.SignatureEd25519", JSch.getConfig("ssh-ed25519"))

        val jsch = JSch()
        // The key as a user pastes it into the app (`ssh-keygen -t ed25519` output).
        jsch.addIdentity("user-key", TEST_ONLY_ED25519_KEY.toByteArray(), null, null)
        val identity = jsch.identityRepository.identities.single()
        assertEquals("ssh-ed25519", identity.algName)
        val signature = assertNotNull(identity.getSignature("session".toByteArray(), "ssh-ed25519"))
        assertTrue(signature.isNotEmpty())
    }

    @Test
    fun curve25519KeyExchangeIsAvailable() {
        useBouncyCastleAlgorithms
        val xdh = Class.forName(JSch.getConfig("xdh")).getDeclaredConstructor().newInstance() as com.jcraft.jsch.XDH
        xdh.init("X25519", 32)
        assertEquals(32, xdh.getQ().size)
    }

    private companion object {
        /** Throwaway key generated for this test only; authorized nowhere. */
        const val TEST_ONLY_ED25519_KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACB6NgGFa19eXYElUCP+Q9PB07svnkfLq/bsNP7legfONwAAAJjMFM9izBTP
YgAAAAtzc2gtZWQyNTUxOQAAACB6NgGFa19eXYElUCP+Q9PB07svnkfLq/bsNP7legfONw
AAAED0ZHWxoYDbMODVNFnq3Y0Qqq5EuRYMnQ9ybG1l9g8i73o2AYVrX15dgSVQI/5D08HT
uy+eR8ur9uw0/uV6B843AAAAD3lwdHVuLXRlc3Qtb25seQECAwQFBg==
-----END OPENSSH PRIVATE KEY-----
"""
    }
}
