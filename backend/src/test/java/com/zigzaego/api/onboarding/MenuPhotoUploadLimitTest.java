package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * 서블릿 업로드 상한을 넘는 메뉴판 사진은 500 이 아니라 400 (지라 S15P31A308-95). MockMvc 는 서블릿 상한을 타지 않으므로
 * 실제 서버를 띄우고 상한을 1KB 로 낮춰 진짜 multipart 요청을 보낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.servlet.multipart.max-file-size=1KB",
		"spring.servlet.multipart.max-request-size=1KB"})
class MenuPhotoUploadLimitTest extends OnboardingTestSupport {

	@LocalServerPort
	private int port;

	@Test
	void 서블릿_업로드_상한을_넘으면_400_job_없음() throws Exception {
		LoggedInStore store = loginNewStore("업로드상한");
		String boundary = "----wiggly" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"menu.jpg\"\r\n"
				+ "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(new byte[4 * 1024]);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/onboarding/jobs/menu-photo"))
				.header("Authorization", store.bearer())
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		HttpResponse<String> response;
		try (HttpClient client = HttpClient.newHttpClient()) {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		}

		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		JsonNode json = jsonMapper.readTree(response.body());
		assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(count("job", store.storeId())).isZero();
	}
}
