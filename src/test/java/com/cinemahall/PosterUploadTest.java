package com.cinemahall;

import com.cinemahall.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PosterUploadTest extends ApiTestBase {

    private byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 6, BufferedImage.TYPE_INT_RGB), "PNG", out);
        return out.toByteArray();
    }

    private Response upload(String token, long movieId, String filename, String contentType, byte[] bytes) throws Exception {
        return send(MockMvcRequestBuilders.multipart("/api/admin/movies/" + movieId + "/poster")
                .file(new MockMultipartFile("file", filename, contentType, bytes)), token, null);
    }

    @Test
    void adminUploadsAPosterThatAnyoneCanSeeAndCanRemoveIt() throws Exception {
        String admin = admin();
        long movie = createMovie(admin, 100);
        assertThat(get(null, "/api/movies/" + movie).body().get("posterUrl").isNull()).isTrue();
        expect(404, get(null, "/api/movies/" + movie + "/poster"));

        byte[] image = png();
        String url = expect(200, upload(admin, movie, "my poster.png", "image/png", image)).body().get("posterUrl").asText();

        assertThat(url).startsWith("/api/movies/" + movie + "/poster");
        Response served = expect(200, get(null, url));
        assertThat(served.raw().getResponse().getContentType()).isEqualTo("image/png");
        assertThat(served.raw().getResponse().getContentAsByteArray()).isEqualTo(image);

        JsonNode removed = expect(200, delete(admin, "/api/admin/movies/" + movie + "/poster")).body();
        assertThat(removed.get("posterUrl").isNull()).isTrue();
        expect(404, get(null, "/api/movies/" + movie + "/poster"));
    }

    @Test
    void replacingAPosterChangesItsUrl() throws Exception {
        String admin = admin();
        long movie = createMovie(admin, 100);
        String first = upload(admin, movie, "a.png", "image/png", png()).body().get("posterUrl").asText();
        String second = upload(admin, movie, "b.png", "image/png", png()).body().get("posterUrl").asText();

        assertThat(second).isNotEqualTo(first);
        expect(200, get(null, second));
    }

    @Test
    void fileContentDecidesTheTypeWhateverTheClientClaims() throws Exception {
        String admin = admin();
        long movie = createMovie(admin, 100);

        byte[] script = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        assertThat(expect(400, upload(admin, movie, "poster.png", "image/png", script)).code()).isEqualTo("INVALID_POSTER");
        assertThat(expect(400, upload(admin, movie, "poster.svg", "image/svg+xml", script)).code()).isEqualTo("INVALID_POSTER");
        assertThat(expect(400, upload(admin, movie, "empty.png", "image/png", new byte[0])).code()).isEqualTo("INVALID_POSTER");

        // A real PNG with a misleading name is stored and served as what it really is.
        String url = expect(200, upload(admin, movie, "../../evil.html", "text/html", png())).body().get("posterUrl").asText();
        assertThat(url).doesNotContain("evil");
        assertThat(get(null, url).raw().getResponse().getContentType()).isEqualTo("image/png");
    }

    @Test
    void oversizedFilesAreRejected() throws Exception {
        String admin = admin();
        long movie = createMovie(admin, 100);
        byte[] tooBig = new byte[3 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, tooBig, 0, 8);

        assertThat(expect(413, upload(admin, movie, "big.png", "image/png", tooBig)).code()).isEqualTo("POSTER_TOO_LARGE");
    }

    @Test
    void onlyAdminsCanChangePosters() throws Exception {
        String admin = admin();
        long movie = createMovie(admin, 100);

        expect(401, upload(null, movie, "a.png", "image/png", png()));
        expect(403, upload(customer(), movie, "a.png", "image/png", png()));
        expect(403, upload(staff(), movie, "a.png", "image/png", png()));
        expect(403, delete(customer(), "/api/admin/movies/" + movie + "/poster"));
        expect(404, upload(admin, 999_999_999L, "a.png", "image/png", png()));
    }
}
