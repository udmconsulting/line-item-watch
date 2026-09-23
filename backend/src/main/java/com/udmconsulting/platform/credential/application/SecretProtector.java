package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.credential.domain.EncryptedSecret;

public interface SecretProtector {

    EncryptedSecret protect(String plaintext, SecretContext context);

    String reveal(EncryptedSecret encryptedSecret, SecretContext context);
}
