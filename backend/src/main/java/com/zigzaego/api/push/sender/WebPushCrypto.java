package com.zigzaego.api.push.sender;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 웹 푸시 본문 암호화(RFC 8291, aes128gcm 한 레코드)와 VAPID 서명(RFC 8292, ES256). JDK 암호화만 쓴다 — 라이브러리 없음 (DECISIONS #95).
 *
 * <p>키는 전부 P-256. 공개키는 비압축 점 65바이트(0x04 || X || Y), 개인키는 32바이트 스칼라, 문자열로는 base64url(패딩 없음).
 */
public final class WebPushCrypto {

	/** 레코드 크기. 본문이 한 레코드에 들어가야 한다 — 알림 본문은 짧다. */
	static final int RECORD_SIZE = 4096;
	private static final int SALT_LENGTH = 16;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final ECParameterSpec P256 = p256();

	private WebPushCrypto() {
	}

	// ---------------------------------------------------------------- 본문 암호화 (RFC 8291)

	/**
	 * @param uaPublic  구독의 keys.p256dh (65바이트)
	 * @param authSecret 구독의 keys.auth (16바이트)
	 * @return aes128gcm 본문 (헤더 salt · rs · keyid + 암호문)
	 */
	public static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext) {
		return encrypt(uaPublic, authSecret, plaintext, generateKeyPair(), randomSalt());
	}

	/** 임시 키 · salt 를 받는 판 — 테스트가 같은 값을 다시 만든다. */
	static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext, KeyPair asKeys, byte[] salt) {
		try {
			byte[] asPublic = encodePoint((ECPublicKey) asKeys.getPublic());
			byte[] ecdh = ecdh((ECPrivateKey) asKeys.getPrivate(), decodePublic(uaPublic));
			byte[][] keyAndNonce = contentKeys(ecdh, authSecret, uaPublic, asPublic, salt);

			byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
			padded[plaintext.length] = 0x02; // 마지막 레코드 구분자
			if (padded.length + TAG_BITS / 8 > RECORD_SIZE) {
				throw new IllegalArgumentException("push payload too large: " + plaintext.length);
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyAndNonce[0], "AES"),
					new GCMParameterSpec(TAG_BITS, keyAndNonce[1]));
			byte[] cipherText = cipher.doFinal(padded);

			ByteBuffer out = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + asPublic.length + cipherText.length);
			out.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(cipherText);
			return out.array();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("web push encryption failed", e);
		}
	}

	/** CEK(16) · NONCE(12). 받는 쪽도 같은 식으로 만든다. */
	static byte[][] contentKeys(byte[] ecdh, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt)
			throws GeneralSecurityException {
		byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic);
		byte[] ikm = hkdf(authSecret, ecdh, keyInfo, 32);
		byte[] cek = hkdf(salt, ikm, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
				new byte[] {0}), 16);
		byte[] nonce = hkdf(salt, ikm, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
				new byte[] {0}), 12);
		return new byte[][] {cek, nonce};
	}

	/** HKDF-SHA256, 출력 32바이트 이하 (한 블록). */
	static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
		byte[] prk = hmac(salt, ikm);
		return Arrays.copyOf(hmac(prk, concat(info, new byte[] {1})), length);
	}

	static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
		KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(privateKey);
		agreement.doPhase(publicKey, true);
		return agreement.generateSecret();
	}

	// ---------------------------------------------------------------- VAPID (RFC 8292)

	/**
	 * @param audience  푸시 서비스 origin (예 https://fcm.googleapis.com)
	 * @param subject   연락처 (mailto: 또는 https:)
	 * @param expiresAt 만료(초, epoch). 24시간 안
	 * @return {@code Authorization} 헤더 값 {@code vapid t=<JWT>, k=<공개키>}
	 */
	public static String vapidAuthorization(String audience, String subject, long expiresAt, byte[] vapidPublic,
			byte[] vapidPrivate) {
		try {
			Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
			String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
			String claims = b64.encodeToString(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + expiresAt + ",\"sub\":\""
					+ json(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
			String signingInput = header + "." + claims;
			Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
			signer.initSign(decodePrivate(vapidPrivate));
			signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			String jwt = signingInput + "." + b64.encodeToString(signer.sign());
			return "vapid t=" + jwt + ", k=" + b64.encodeToString(vapidPublic);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("vapid signing failed", e);
		}
	}

	/** 공개키 · 개인키가 같은 P-256 키 쌍인지. 설정을 읽을 때 한 번 본다. */
	public static boolean isKeyPair(byte[] publicKey, byte[] privateKey) {
		try {
			ECPublicKey pub = decodePublic(publicKey);
			ECPrivateKey priv = decodePrivate(privateKey);
			Signature signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(priv);
			signer.update(new byte[] {1});
			byte[] sig = signer.sign();
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(pub);
			verifier.update(new byte[] {1});
			return verifier.verify(sig);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- 키

	public static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 비압축 점 65바이트. */
	public static byte[] encodePoint(ECPublicKey key) {
		return concat(new byte[] {4}, unsigned32(key.getW().getAffineX()), unsigned32(key.getW().getAffineY()));
	}

	/** 개인키 스칼라 32바이트. */
	public static byte[] encodePrivate(ECPrivateKey key) {
		return unsigned32(key.getS());
	}

	static ECPublicKey decodePublic(byte[] point) throws GeneralSecurityException {
		if (point.length != 65 || point[0] != 4) {
			throw new IllegalArgumentException("not an uncompressed P-256 point");
		}
		ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
				new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
		return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
	}

	static ECPrivateKey decodePrivate(byte[] scalar) throws GeneralSecurityException {
		if (scalar.length != 32) {
			throw new IllegalArgumentException("not a 32-byte P-256 private key");
		}
		return (ECPrivateKey) KeyFactory.getInstance("EC")
				.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
	}

	public static byte[] base64Url(String value) {
		return Base64.getUrlDecoder().decode(value.strip().replace('+', '-').replace('/', '_').replace("=", ""));
	}

	// ---------------------------------------------------------------- 도구

	private static ECParameterSpec p256() {
		try {
			AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
			params.init(new ECGenParameterSpec("secp256r1"));
			return params.getParameterSpec(ECParameterSpec.class);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] randomSalt() {
		byte[] salt = new byte[SALT_LENGTH];
		RANDOM.nextBytes(salt);
		return salt;
	}

	private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data);
	}

	private static byte[] unsigned32(BigInteger value) {
		byte[] raw = value.toByteArray();
		byte[] out = new byte[32];
		int copy = Math.min(raw.length, 32);
		System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
		return out;
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}

	private static String json(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
