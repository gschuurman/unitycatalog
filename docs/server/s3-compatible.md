# S3-compatible storage

Unity Catalog can vend credentials for an S3-compatible object store instead of AWS S3, as long as
the store also serves the STS `AssumeRole` API with inline session policies on the same endpoint.
[RustFS](https://rustfs.com) does this, for example.

Credential vending works the same as on AWS: the server calls `AssumeRole` with a session policy
scoped to the table or volume path, and hands the resulting temporary credentials to the client.
The store enforces that policy.

## Configuration

Set the endpoint next to the existing AWS settings in `server.properties`.

For external locations and storage credentials (the recommended setup, see [AWS](aws.md)):

```ini
aws.masterRoleArn=arn:aws:iam::000000000000:user/unity-catalog
aws.accessKey=<access key of a store user allowed to call sts:AssumeRole>
aws.secretKey=<its secret key>
aws.region=us-east-1
aws.endpointUrl=http://rustfs.storage.svc:9000
```

For a legacy per-bucket config:

```ini
s3.bucketPath.0=s3://lake
s3.region.0=us-east-1
s3.awsRoleArn.0=arn:aws:iam::000000000000:role/lake
s3.accessKey.0=<access key of a store user allowed to call sts:AssumeRole>
s3.secretKey.0=<its secret key>
s3.endpointUrl.0=http://rustfs.storage.svc:9000
```

Leave the endpoint blank for AWS S3.

## Behaviour with an endpoint set

- The STS client calls the endpoint instead of AWS STS.
- The session policy leaves out its KMS statement. That statement only applies to AWS KMS, and
  S3-compatible stores reject policies that contain KMS actions they don't recognise.
- Vended credentials carry `endpoint_url`. The server's own file access and Iceberg REST table
  configs use it with path-style access.
- The bucket region is not looked up from AWS. The configured region is used, or `us-east-1` if
  none is set.

Many S3-compatible stores ignore the role ARN and scope the session to the calling user instead.
The role ARN must still be a well-formed ARN, because the server reads the IAM partition from it.

## Clients

Spark and other Hadoop clients must reach the same store. Set the endpoint and path-style access in
the client configuration:

```
spark.hadoop.fs.s3a.endpoint=http://rustfs.storage.svc:9000
spark.hadoop.fs.s3a.path.style.access=true
```
