package com.zigzaego.api.purchasedoc.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

/** S3 구현의 예외 계약이 파일 구현과 같은지 (ADR-0013). S3 는 S3Mock 컨테이너. */
class S3ReceiptStorageTest {

	private static final String BUCKET = "receipts-unit";
	private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, 1, 2};

	private static S3Client client;
	private static S3ReceiptStorage storage;

	@BeforeAll
	static void setUp() {
		client = S3MockContainer.client();
		S3MockContainer.createBucket(client, BUCKET);
		storage = new S3ReceiptStorage(client, BUCKET);
	}

	@AfterAll
	static void tearDown() {
		client.close();
	}

	@Test
	void save_키는_가게_job_uuid_확장자이고_read_가_같은_바이트를_돌려준다() throws IOException {
		String key = storage.save(7, 42, "image/png", JPG);

		assertThat(key).matches("7/42/[0-9a-f-]{36}[.]png");
		assertThat(storage.read(key)).containsExactly(JPG);
		assertThat(client.headObject(b -> b.bucket(BUCKET).key(key)).contentType()).isEqualTo("image/png");
	}

	@Test
	void read_없는_키는_NoSuchFileException() {
		assertThatThrownBy(() -> storage.read("7/42/없는-파일.jpg")).isInstanceOf(NoSuchFileException.class);
	}

	@Test
	void read_버킷이_없으면_런타임_예외가_아니라_IOException() {
		S3ReceiptStorage missing = new S3ReceiptStorage(client, "receipts-missing-bucket");

		assertThatThrownBy(() -> missing.read("7/42/a.jpg")).isInstanceOf(IOException.class);
	}

	@Test
	void save_실패는_UncheckedIOException() {
		S3ReceiptStorage missing = new S3ReceiptStorage(client, "receipts-missing-bucket");

		assertThatThrownBy(() -> missing.save(7, 42, "image/jpeg", JPG)).isInstanceOf(UncheckedIOException.class);
	}

	@Test
	void deleteQuietly_지우고_없는_키나_틀린_키도_던지지_않는다() {
		String key = storage.save(7, 43, "image/jpeg", JPG);

		storage.deleteQuietly(key);

		assertThatThrownBy(() -> storage.read(key)).isInstanceOf(NoSuchFileException.class);
		assertThatCode(() -> storage.deleteQuietly(key)).doesNotThrowAnyException();
		assertThatCode(() -> storage.deleteQuietly("../밖")).doesNotThrowAnyException();
	}

	@Test
	void read_빈_키_절대_경로_상위_경로는_거절() {
		for (String bad : new String[] {"", "/7/42/a.jpg", "7/../../a.jpg"}) {
			assertThatThrownBy(() -> storage.read(bad)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void 버킷_설정이_없으면_만들지_않는다() {
		assertThatThrownBy(() -> new S3ReceiptStorage(client, " ")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void storage_s3_인데_버킷이_없으면_컨텍스트가_뜨지_않고_file_이면_S3_빈이_없다() {
		S3MockContainer.useDummyAwsCredentials();
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(S3ReceiptStorageConfig.class, S3ReceiptStorage.class, ReceiptFileStorage.class);

		runner.withPropertyValues("app.receipt.storage=s3").run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("app.receipt.storage=s3", "app.receipt.s3.bucket=" + BUCKET,
						"app.receipt.s3.endpoint=" + S3MockContainer.endpoint())
				.run(ctx -> assertThat(ctx).hasSingleBean(ReceiptStorage.class).hasSingleBean(S3ReceiptStorage.class));
		runner.run(ctx -> assertThat(ctx).hasSingleBean(ReceiptFileStorage.class).doesNotHaveBean(S3Client.class));
	}
}
