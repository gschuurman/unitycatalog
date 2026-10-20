package io.unitycatalog.server.service.credential.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.util.Optional;
import lombok.SneakyThrows;

/**
 * Support for S3-compatible object stores (RustFS, Ceph RGW, ...) that serve both the S3 API and an
 * STS AssumeRole endpoint at a custom URL instead of AWS.
 *
 * <p>Such a store is selected by setting {@code s3.endpointUrl.N} on a per-bucket config, or {@code
 * aws.endpointUrl} for the master role used with external locations. The STS client is pointed at
 * that URL, and the URL is vended alongside the temporary credential so clients address the same
 * store (with path-style access, since these stores rarely have per-bucket DNS).
 */
public final class S3CompatibleEndpoint {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private S3CompatibleEndpoint() {}

  /** The configured endpoint, or empty when the config targets AWS itself. */
  public static Optional<URI> of(S3StorageConfig config) {
    if (config == null || config.getEndpointUrl() == null || config.getEndpointUrl().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(URI.create(config.getEndpointUrl().trim()));
  }

  /**
   * Drops the KMS statements from an AssumeRole session policy. They only make sense on AWS (the
   * {@code kms:ViaService} condition names AWS S3 hosts), and S3-compatible stores reject the
   * policy outright on actions they don't know, such as {@code kms:GenerateDataKey*}.
   */
  @SneakyThrows
  public static String withoutKmsStatements(String policy) {
    JsonNode root = JSON_MAPPER.readTree(policy);
    ArrayNode statements = (ArrayNode) root.path("Statement");
    for (int i = statements.size() - 1; i >= 0; i--) {
      if (isKmsOnly(statements.get(i).path("Action"))) {
        statements.remove(i);
      }
    }
    return JSON_MAPPER.writeValueAsString(root);
  }

  private static boolean isKmsOnly(JsonNode actions) {
    if (actions.isTextual()) {
      return actions.asText().startsWith("kms:");
    }
    if (!actions.isArray() || actions.isEmpty()) {
      return false;
    }
    for (JsonNode action : actions) {
      if (!action.asText().startsWith("kms:")) {
        return false;
      }
    }
    return true;
  }
}
