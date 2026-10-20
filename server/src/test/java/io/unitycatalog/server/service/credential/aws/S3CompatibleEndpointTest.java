package io.unitycatalog.server.service.credential.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.model.AwsIamRoleRequest;
import io.unitycatalog.server.model.CreateCredentialRequest;
import io.unitycatalog.server.model.CredentialPurpose;
import io.unitycatalog.server.model.TemporaryCredentials;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.persist.utils.ExternalLocationUtils;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.credential.StorageCredentialVendor;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

public class S3CompatibleEndpointTest {
  private static final String ENDPOINT = "http://rustfs.storage.svc:9000";
  private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lake";
  private static final String PATH = "s3://lake/catalog/table";

  private final ObjectMapper mapper = new ObjectMapper();
  private ServerProperties serverProperties;
  private ExternalLocationUtils externalLocationUtils;
  private StsClientBuilder stsClientBuilder;
  private ArgumentCaptor<AssumeRoleRequest> assumeRole;

  @BeforeEach
  public void setUp() {
    serverProperties = mock(ServerProperties.class);
    externalLocationUtils = mock(ExternalLocationUtils.class);
    doReturn(Optional.empty())
        .when(externalLocationUtils)
        .getExternalLocationCredentialDaoForPath(any());
    doReturn(S3StorageConfig.builder().build())
        .when(serverProperties)
        .getS3MasterRoleConfiguration();

    StsClient stsClient = mock(StsClient.class);
    assumeRole = ArgumentCaptor.forClass(AssumeRoleRequest.class);
    when(stsClient.assumeRole(assumeRole.capture()))
        .thenReturn(
            AssumeRoleResponse.builder()
                .credentials(
                    Credentials.builder()
                        .accessKeyId("ak")
                        .secretAccessKey("sk")
                        .sessionToken("st")
                        .build())
                .build());
    stsClientBuilder = mock(StsClientBuilder.class);
    when(stsClientBuilder.region(any())).thenReturn(stsClientBuilder);
    when(stsClientBuilder.credentialsProvider(any())).thenReturn(stsClientBuilder);
    when(stsClientBuilder.endpointOverride(any())).thenReturn(stsClientBuilder);
    when(stsClientBuilder.build()).thenReturn(stsClient);
  }

  private TemporaryCredentials vend(CredentialContext.Privilege privilege) {
    AwsCredentialVendor awsVendor =
        new AwsCredentialVendor(serverProperties, () -> stsClientBuilder);
    CloudCredentialVendor cloudVendor = new CloudCredentialVendor(awsVendor, null, null);
    return new StorageCredentialVendor(cloudVendor, externalLocationUtils)
        .vendCredential(NormalizedURL.from(PATH), Set.of(privilege));
  }

  private void perBucketConfig(String endpointUrl) {
    when(serverProperties.getS3Configurations())
        .thenReturn(
            Map.of(
                NormalizedURL.from("s3://lake"),
                S3StorageConfig.builder()
                    .bucketPath("s3://lake")
                    .region("us-east-1")
                    .awsRoleArn(ROLE_ARN)
                    .accessKey("uc")
                    .secretKey("uc-secret")
                    .endpointUrl(endpointUrl)
                    .build()));
  }

  private String policyText() {
    return assumeRole.getValue().policy();
  }

  @Test
  public void perBucketEndpointIsUsedForStsAndVended() {
    perBucketConfig(ENDPOINT);

    TemporaryCredentials credentials = vend(CredentialContext.Privilege.UPDATE);

    verify(stsClientBuilder).endpointOverride(URI.create(ENDPOINT));
    assertThat(credentials.getEndpointUrl()).isEqualTo(ENDPOINT);
    assertThat(credentials.getAwsTempCredentials().getSessionToken()).isEqualTo("st");
    assertThat(policyText()).doesNotContain("kms:").contains("s3:PutObject");
  }

  @Test
  public void awsBucketKeepsKmsAndVendsNoEndpoint() {
    perBucketConfig(null);

    TemporaryCredentials credentials = vend(CredentialContext.Privilege.SELECT);

    verify(stsClientBuilder, never()).endpointOverride(any());
    assertThat(credentials.getEndpointUrl()).isNull();
    assertThat(policyText()).contains("kms:Decrypt");
  }

  @Test
  public void blankEndpointMeansAws() {
    perBucketConfig("  ");

    TemporaryCredentials credentials = vend(CredentialContext.Privilege.SELECT);

    verify(stsClientBuilder, never()).endpointOverride(any());
    assertThat(credentials.getEndpointUrl()).isNull();
  }

  @Test
  public void externalLocationUsesMasterEndpoint() {
    CredentialDAO credentialDAO =
        CredentialDAO.from(
            new CreateCredentialRequest()
                .name("lake")
                .purpose(CredentialPurpose.STORAGE)
                .awsIamRole(new AwsIamRoleRequest().roleArn(ROLE_ARN)),
            "test-user");
    doReturn(Optional.of(credentialDAO))
        .when(externalLocationUtils)
        .getExternalLocationCredentialDaoForPath(any());
    when(serverProperties.getS3Configurations()).thenReturn(Map.of());
    doReturn(
            S3StorageConfig.builder()
                .region("us-east-1")
                .accessKey("uc")
                .secretKey("uc-secret")
                .endpointUrl(ENDPOINT)
                .build())
        .when(serverProperties)
        .getS3MasterRoleConfiguration();

    TemporaryCredentials credentials = vend(CredentialContext.Privilege.SELECT);

    verify(stsClientBuilder).endpointOverride(URI.create(ENDPOINT));
    assertThat(credentials.getEndpointUrl()).isEqualTo(ENDPOINT);
    assertThat(assumeRole.getValue().externalId())
        .isEqualTo(credentialDAO.getAwsIamRoleResponse().getExternalId());
    assertThat(policyText()).doesNotContain("kms:");
  }

  @Test
  public void endpointIsReadFromServerProperties() {
    Properties props = new Properties();
    props.setProperty("aws.endpointUrl", ENDPOINT);
    props.setProperty("s3.bucketPath.0", "s3://lake");
    props.setProperty("s3.region.0", "us-east-1");
    props.setProperty("s3.awsRoleArn.0", ROLE_ARN);
    props.setProperty("s3.endpointUrl.0", "http://other:9000");
    ServerProperties properties = new ServerProperties(props);

    assertThat(properties.getS3MasterRoleConfiguration().getEndpointUrl()).isEqualTo(ENDPOINT);
    assertThat(
            properties.getS3Configurations().get(NormalizedURL.from("s3://lake")).getEndpointUrl())
        .isEqualTo("http://other:9000");
  }

  @Test
  public void withoutKmsStatementsKeepsEverythingElse() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(CredentialContext.Privilege.UPDATE),
            List.of(NormalizedURL.from(PATH)),
            ROLE_ARN,
            Region.US_EAST_1);
    JsonNode before = mapper.readTree(policy);
    JsonNode after = mapper.readTree(S3CompatibleEndpoint.withoutKmsStatements(policy));

    assertThat(policy).contains("kms:GenerateDataKey*");
    assertThat(after.get("Statement")).hasSize(before.get("Statement").size() - 1);
    assertThat(after.toString()).doesNotContain("kms:").contains("s3:ListBucket", "s3:PutObject");
    assertThat(after.get("Version")).isEqualTo(before.get("Version"));
  }

  @Test
  public void withoutKmsStatementsKeepsMixedStatements() {
    String policy =
        "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"},"
            + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"kms:Decrypt\"],"
            + "\"Resource\":\"*\"}]}";

    assertThat(S3CompatibleEndpoint.withoutKmsStatements(policy))
        .isEqualTo(
            "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"kms:Decrypt\"],"
                + "\"Resource\":\"*\"}]}");
  }
}
