package kz.ncanode.service;

import kz.gov.pki.kalkan.jce.provider.KalkanProvider;
import kz.ncanode.constants.MessageConstants;
import kz.ncanode.dto.Signer;
import kz.ncanode.dto.request.SignerRequest;
import kz.ncanode.exception.KeyException;
import kz.ncanode.exception.ServerException;
import kz.ncanode.util.KeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeyService {
    private final KalkanProvider kalkanProvider;

    /**
     * Читает ключ p12
     *
     * @param key Key in Base64 format
     * @param password Password
     * @param alias Алиас ключа. Может быть null
     * @return Ключ ЭЦП
     */
    public Signer read(String key, String password, String alias) throws KeyException {
        KeyStore store;

        try {
            store = KeyStore.getInstance("PKCS12", kalkanProvider);
        } catch (KeyStoreException e) {
            log.error(MessageConstants.KEY_ENGINE_ERROR, e);
            throw new KeyException(MessageConstants.KEY_ENGINE_ERROR, e);
        }

        byte[] decodedKey;

        try {
            decodedKey = Base64.getDecoder().decode(key);
        } catch (Exception e) {
            log.error(MessageConstants.KEY_INVALID_BASE64, e);
            throw new KeyException(MessageConstants.KEY_INVALID_BASE64, e);
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedKey)) {
            store.load(inputStream, password.toCharArray());
        } catch (IOException | NoSuchAlgorithmException | CertificateException e) {
            final String message = createMessageFromException(e);
            log.error(message, e);
            throw new KeyException(message, e);
        }

        // Checking for alias
        var aliases = KeyUtil.getAliases(store);

        if (aliases.isEmpty()) {
            log.error(MessageConstants.KEY_ALIASES_NOT_FOUND);
            throw new KeyException(MessageConstants.KEY_ALIASES_NOT_FOUND);
        }

        if (alias != null && !aliases.contains(alias)) {
            final String err = String.format(MessageConstants.KEY_ALIAS_NOT_FOUND, alias);
            log.error(err);
            throw new KeyException(err);
        } else {
            alias = aliases.get(0);
        }

        return Signer.builder()
            .key(store)
            .alias(alias)
            .password(password)
            .build();
    }

    public List<Signer> read(final List<SignerRequest> signers) {
        return IntStream.range(0, signers.size())
            .mapToObj(i -> tryReadKey(signers, i))
            .collect(Collectors.toList());
    }

    private Signer tryReadKey(List<SignerRequest> signers, Integer index) {
        SignerRequest signerRequest = signers.get(index);

        try {
            return read(signerRequest.getKey(), signerRequest.getPassword(), signerRequest.getKeyAlias());
        } catch (KeyException e) {
            final String errorMessage = String.format("signers[%d]: %s", index, e.getMessage());
            log.error(errorMessage, e.getCause());
            throw new ServerException(errorMessage, e.getCause());
        }
    }

    private String createMessageFromException(Exception e) {
        return switch (e.getMessage()) {
            case "stream does not represent a PKCS12 key store" -> MessageConstants.KEY_INVALID_FORMAT;
            case "PKCS12 key store mac invalid - wrong password or corrupted file." -> MessageConstants.KEY_INVALID_PASSWORD;
            default -> MessageConstants.KEY_UNKNOWN_ERROR;
        };
    }
}
