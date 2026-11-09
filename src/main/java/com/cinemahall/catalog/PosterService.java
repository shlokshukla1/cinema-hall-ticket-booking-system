package com.cinemahall.catalog;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.catalog.CatalogDtos.MovieView;
import com.cinemahall.catalog.CatalogRepositories.MovieRepository;
import com.cinemahall.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Stores admin-uploaded posters on disk. The image type comes from the file's own bytes, never from the client's
 * file name or content type, and the stored name is generated, so an upload cannot choose its path. SVG is not
 * accepted, because an SVG can carry scripts.
 */
@Service
public class PosterService {

    private static final Logger log = LoggerFactory.getLogger(PosterService.class);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final Map<String, MediaType> TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "webp", MediaType.parseMediaType("image/webp"));

    public record Poster(Resource file, MediaType type) {
    }

    private final MovieRepository movies;
    private final AuditService audit;
    private final Path folder;
    private final long maxBytes;

    public PosterService(MovieRepository movies, AuditService audit,
                         @Value("${app.posters.dir}") String folder,
                         @Value("${app.posters.max-bytes}") long maxBytes) {
        this.movies = movies;
        this.audit = audit;
        this.folder = Path.of(folder);
        this.maxBytes = maxBytes;
    }

    @Transactional
    public MovieView upload(AuthUser actor, long movieId, MultipartFile upload) {
        Movie movie = movies.findById(movieId).orElseThrow(() -> ApiException.notFound("Movie"));
        if (upload.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "POSTER_TOO_LARGE",
                    "Choose an image of at most " + maxBytes / (1024 * 1024) + " MB");
        }
        try {
            byte[] bytes = upload.getBytes();
            String extension = detectExtension(bytes);
            if (extension == null) {
                throw ApiException.badRequest("INVALID_POSTER", "Choose a JPEG, PNG or WebP image");
            }
            Files.createDirectories(folder);
            String name = "movie-" + movieId + "-" + UUID.randomUUID() + "." + extension;
            Files.write(folder.resolve(name), bytes);
            String previous = movie.getPosterFile();
            movie.setPosterFile(name);
            movies.flush();
            audit.record(actor, "MOVIE_POSTER_UPDATED", "MOVIE", movieId, movie.getTitle());
            deleteQuietly(previous);
            return MovieView.from(movie);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not store poster", ex);
        }
    }

    @Transactional
    public MovieView remove(AuthUser actor, long movieId) {
        Movie movie = movies.findById(movieId).orElseThrow(() -> ApiException.notFound("Movie"));
        String previous = movie.getPosterFile();
        if (previous == null) {
            return MovieView.from(movie);
        }
        movie.setPosterFile(null);
        movies.flush();
        audit.record(actor, "MOVIE_POSTER_REMOVED", "MOVIE", movieId, movie.getTitle());
        deleteQuietly(previous);
        return MovieView.from(movie);
    }

    @Transactional(readOnly = true)
    public Poster load(long movieId) {
        String name = movies.findById(movieId).map(Movie::getPosterFile)
                .orElseThrow(() -> ApiException.notFound("Poster"));
        Path file = folder.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("Poster");
        }
        return new Poster(new FileSystemResource(file), TYPES.get(name.substring(name.lastIndexOf('.') + 1)));
    }

    /** @return the file extension for a supported image, or null if the bytes are not one */
    static String detectExtension(byte[] bytes) {
        if (startsWith(bytes, PNG)) {
            return "png";
        }
        if (startsWith(bytes, JPEG)) {
            return "jpg";
        }
        if (bytes.length > 12
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private void deleteQuietly(String name) {
        if (name == null) {
            return;
        }
        try {
            Files.deleteIfExists(folder.resolve(name));
        } catch (IOException ex) {
            log.warn("Could not delete old poster {}: {}", name, ex.getMessage());
        }
    }
}
