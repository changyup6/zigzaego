package com.zigzaego.api.push.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 웹 푸시 암호화 · VAPID 서명 (S15P31A308-248). 받는 쪽(브라우저)이 하는 복호화를 RFC 8291 대로 다시 해 원문이 나오는지,
 * VAPID JWT 가 공개키로 검증되는지 본다.
 */
class WebPushCryptoTest {

	@Test
	void 브라우저_키로_암호화한_본문을_브라우저가_풀면_원문이_나온다() throws Exception {
		KeyPair ua = WebPushCrypto.generateKeyPair();
		byte[] uaPublic = WebPushCrypto.encodePoint((ECPublicKey) ua.getPublic());
		byte[] auth = new byte[16];
		Arrays.fill(auth, (byte) 7);
		String message = "{\"title\":\"오늘 재고 확인\",\"url\":\"/closing-check\"}";

		byte[] body = WebPushCrypto.encrypt(uaPublic, auth, message.getBytes(StandardCharsets.UTF_8));

		ByteBuffer in = ByteBuffer.wrap(body);
		byte[] salt = new byte[16];
		in.get(salt);
		assertThat(in.getInt()).isEqualTo(WebPushCrypto.RECORD_SIZE);
		byte[] asPublic = new byte[in.get()];
		in.get(asPublic);
		byte[] cipherText = new byte[in.remaining()];
		in.get(cipherText);

		byte[] ecdh = WebPushCrypto.ecdh((ECPrivateKey) ua.getPrivate(), WebPushCrypto.decodePublic(asPublic));
		byte[][] keys = WebPushCrypto.contentKeys(ecdh, auth, uaPublic, asPublic, salt);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
		byte[] plain = cipher.doFinal(cipherText);

		assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
		assertThat(new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8)).isEqualTo(message);
	}

	@Test
	void VAPID_JWT_는_공개키로_검증되고_aud_exp_sub_를_담는다() throws Exception {
		KeyPair vapid = WebPushCrypto.generateKeyPair();
		byte[] pub = WebPushCrypto.encodePoint((ECPublicKey) vapid.getPublic());
		byte[] priv = WebPushCrypto.encodePrivate((ECPrivateKey) vapid.getPrivate());

		String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com", "mailto:team@example.com",
				1_800_000_000L, pub, priv);

		assertThat(header).startsWith("vapid t=").contains(", k=" + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(pub));
		String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
		String[] parts = jwt.split("\\.");
		String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		assertThat(claims).isEqualTo(
				"{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800000000,\"sub\":\"mailto:team@example.com\"}");
		Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
		verifier.initVerify(vapid.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	void 키_쌍이_맞는지_본다() {
		KeyPair a = WebPushCrypto.generateKeyPair();
		KeyPair b = WebPushCrypto.generateKeyPair();
		byte[] aPub = WebPushCrypto.encodePoint((ECPublicKey) a.getPublic());
		byte[] aPriv = WebPushCrypto.encodePrivate((ECPrivateKey) a.getPrivate());
		byte[] bPriv = WebPushCrypto.encodePrivate((ECPrivateKey) b.getPrivate());

		assertThat(WebPushCrypto.isKeyPair(aPub, aPriv)).isTrue();
		assertThat(WebPushCrypto.isKeyPair(aPub, bPriv)).isFalse();
		assertThat(WebPushCrypto.isKeyPair(new byte[3], aPriv)).isFalse();
	}
}
