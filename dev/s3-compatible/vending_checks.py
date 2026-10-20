"""Checks that credentials vended by a running UC server work against an S3-compatible store and
that the store enforces their session policy. Run by rustfs-vending-test.sh."""

import json
import os
import sys
import urllib.error
import urllib.request

import boto3
from botocore.exceptions import ClientError

UC = os.environ["UC_URL"]
ENDPOINT = os.environ["S3_ENDPOINT"]
BUCKET = os.environ["BUCKET"]


def post(path, body):
    request = urllib.request.Request(
        f"{UC}/{path}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        sys.exit(f"FAIL: POST {path} -> {e.code} {e.read().decode()}")


def s3(access_key, secret_key, session_token=None):
    return boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        region_name="us-east-1",
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        aws_session_token=session_token,
    )


def denied(call):
    try:
        call()
    except ClientError as e:
        return e.response["Error"]["Code"] == "AccessDenied"
    return False


def check(ok, message):
    if not ok:
        sys.exit(f"FAIL: {message}")
    print(f"ok: {message}")


admin = s3(os.environ["S3_ACCESS_KEY"], os.environ["S3_SECRET_KEY"])
try:
    admin.create_bucket(Bucket=BUCKET)
except ClientError as e:
    if e.response["Error"]["Code"] not in ("BucketAlreadyOwnedByYou", "BucketAlreadyExists"):
        raise

post("catalogs", {"name": "s3c"})
post("schemas", {"name": "sch", "catalog_name": "s3c"})
table_id = post(
    "tables",
    {
        "name": "t",
        "catalog_name": "s3c",
        "schema_name": "sch",
        "table_type": "EXTERNAL",
        "data_source_format": "DELTA",
        "storage_location": f"s3://{BUCKET}/tables/t",
        "columns": [
            {
                "name": "id",
                "type_name": "INT",
                "type_text": "int",
                "type_json": '{"name":"id","type":"integer","nullable":true,"metadata":{}}',
                "position": 0,
                "nullable": True,
            }
        ],
    },
)["table_id"]


def vend(operation):
    credentials = post(
        "temporary-table-credentials", {"table_id": table_id, "operation": operation}
    )
    aws = credentials["aws_temp_credentials"]
    client = s3(aws["access_key_id"], aws["secret_access_key"], aws["session_token"])
    return credentials, client


credentials, rw = vend("READ_WRITE")
check(credentials.get("endpoint_url") == ENDPOINT, f"vended endpoint is {ENDPOINT}")
check(
    credentials["aws_temp_credentials"]["access_key_id"] != os.environ["S3_ACCESS_KEY"],
    "vended keys are temporary, not the server's own",
)
rw.put_object(Bucket=BUCKET, Key="tables/t/part-0.parquet", Body=b"data")
check(
    rw.get_object(Bucket=BUCKET, Key="tables/t/part-0.parquet")["Body"].read() == b"data",
    "READ_WRITE writes and reads inside the table",
)
listed = rw.list_objects_v2(Bucket=BUCKET, Prefix="tables/t/")
check(
    [o["Key"] for o in listed.get("Contents", [])] == ["tables/t/part-0.parquet"],
    "READ_WRITE lists the table",
)
check(
    denied(lambda: rw.put_object(Bucket=BUCKET, Key="tables/other/x", Body=b"x")),
    "READ_WRITE can't write outside the table",
)
check(
    denied(lambda: rw.list_objects_v2(Bucket=BUCKET, Prefix="tables/")),
    "READ_WRITE can't list outside the table",
)

_, ro = vend("READ")
check(
    ro.get_object(Bucket=BUCKET, Key="tables/t/part-0.parquet")["Body"].read() == b"data",
    "READ reads inside the table",
)
check(
    denied(lambda: ro.put_object(Bucket=BUCKET, Key="tables/t/part-1.parquet", Body=b"x")),
    "READ can't write",
)
check(
    denied(lambda: ro.delete_object(Bucket=BUCKET, Key="tables/t/part-0.parquet")),
    "READ can't delete",
)

# External location mode: the master STS client (aws.endpointUrl) assumes the storage credential's
# role, passing its external id.
post(
    "credentials",
    {
        "name": "lake",
        "purpose": "STORAGE",
        "aws_iam_role": {"role_arn": "arn:aws:iam::000000000000:role/lake"},
    },
)
post(
    "external-locations",
    {"name": "ext", "url": f"s3://{BUCKET}/ext", "credential_name": "lake"},
)
table_id = post(
    "tables",
    {
        "name": "t_ext",
        "catalog_name": "s3c",
        "schema_name": "sch",
        "table_type": "EXTERNAL",
        "data_source_format": "DELTA",
        "storage_location": f"s3://{BUCKET}/ext/t",
        "columns": [
            {
                "name": "id",
                "type_name": "INT",
                "type_text": "int",
                "type_json": '{"name":"id","type":"integer","nullable":true,"metadata":{}}',
                "position": 0,
                "nullable": True,
            }
        ],
    },
)["table_id"]
credentials, ext = vend("READ_WRITE")
check(credentials.get("endpoint_url") == ENDPOINT, "external location: vended endpoint")
ext.put_object(Bucket=BUCKET, Key="ext/t/part-0.parquet", Body=b"data")
check(
    ext.get_object(Bucket=BUCKET, Key="ext/t/part-0.parquet")["Body"].read() == b"data",
    "external location: READ_WRITE writes and reads inside the table",
)
check(
    denied(lambda: ext.put_object(Bucket=BUCKET, Key="tables/t/x", Body=b"x")),
    "external location: READ_WRITE can't write to another table",
)

print(f"PASS: vended credentials work against {ENDPOINT} and the session policy is enforced")
