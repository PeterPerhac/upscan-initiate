# upscan-initiate <a name="top"></a>

Microservice for initiating the upload of files created externally to HMRC estate. These could be from members of the public or third-party services.
This service is not for transfer of files from one HMRC service to another. See the Transmission Service as documented in Confluence for this use-case.

[![Build Status](https://travis-ci.org/hmrc/upscan-initiate.svg)](https://travis-ci.org/hmrc/upscan-initiate) [ ![Download](https://api.bintray.com/packages/hmrc/releases/upscan-initiate/images/download.svg) ](https://bintray.com/hmrc/releases/upscan-initiate/_latestVersion)

# TLDR

- *We strongly advise against hardcoding the "fields" in the response of `initiate` and `v2/initiate`. These are subject to change.*
- *The file must be the last field in the actual upload request.*
- *You must use multipart encoding (multipart/form-data) NOT application/x-www-form-urlencoded. The error message returned by AWS is obscure when the wrong content type is used.*

# Upscan user manual

## Contents
1. [Introduction](#introduction)
2. [Onboarding requirements](#onboard)
3. [File upload workflow](#workflow)
4. [Service usage](#service)
  a. [Requesting a URL to upload to](#service__request)
  b. [The file upload](#service__upload)
  c. [File upload outcome](#service__uoutcome)
  d. [File processing outcome](#service__poutcome)
    i. [Success](#service__poutcome__success)
    ii. [Failure](#service__poutcome__failure)
5. [Whitelisting client services](#whitelist)
6. [Error handling](#error)
7. [Design considerations](#design)
  a. [Uploading multiple files](#design__multiple)
  b. [Security](#design__security)
  c. [File metadata](#design__metadata)
8. [Architecture of the service](#architecture)
9. [Running and maintenance of the service](#run)
  a. [Running locally](#run__local)
10. [Appendix](#appendix)
  a. [Quick reference figures](#appendix__figures)
  b. [Related projects, useful links](#appendix__links)
    i. [Testing](#appendix__links__testing)
    ii. [Slack](#appendix__links__slack)
  c. [License](#appendix__license)
    

## Introduction <a name="introduction"></a>

Please also read the Upscan documentation in Confluence, this is in the "Platform Services" space.

In this "user manual" the collection of microservices that make up Upscan are discussed, not just `upscan-initiate`. This documentation is here as `upscan-initiate` is the microservice which developers will interact with directly.

The Upscan service allows consuming services to orchestrate the uploading of files. Upscan provides
temporary storage of the uploaded file, ensures that the file isn't harmful (doesn't contain viruses) and verifies against predefined restrictions provided by the consuming service (e.g. file type & file size).
Once the upload URL has been requested, upload and verification of a file are performed asynchronously without the involvement of the consuming service.

[[Back to the top]](#top)

## Onboarding requirements <a name="onboard"></a>
To use Upscan, the consuming service must let Platform Services know :
- the `User-Agent` request header of the service so it can be [whitelisted](#whitelist)
- how long they would like download URLs for their files to be valid for \[max. 7 days]

[[Back to the top]](#top)

## File upload workflow <a name="workflow"></a>

* Consuming service requests upload of a single file. It makes a HTTP POST call to the `/upscan/initiate` endpoint with details of the requested upload (including a callback URL and optional file size constraints)
* Upscan service replies with a template of the HTTP POST form that will be used to upload the file. A unique file reference is also provided to allow the consuming service to correlate the upload request with notifications to the callback URL provided
* Consuming service passes the form details to the end-user, either via a control on a webpage or to an internal/external service which will upload the file
* The end user uploads the file
* Upscan service performs all checks on the uploaded file
* If file the file passes all checks, a notification is sent as a POST request to a consuming service. This notification contains the URL to GET the file from
* Consuming service downloads the file using provided URL or passes this URL on to another service which will make use of the file location
* After specified time, the file is automatically removed from the remote storage. Upscan does NOT keep files indefinitely
* If the file fails a check, a notification is sent to the consuming service containing information on the failed check. The file is unavailable for retrieval
* If the consuming service fails to respond to the callback request (e.g. the consuming service is down, the consuming service answered with an HTTP status code other than 2xx), the callback will be retried up to a maximum of 30 retries. The time interval between the retries is 60 seconds
Configuration of these values is here (https://github.com/hmrc/upscan-infrastructure/blob/master/modules/sqs/main.tf)

[[Back to the top]](#top)

## Service usage <a name="service"></a>

### Requesting a URL to upload to <a name="service__request"></a>

Assuming the consuming service is whitelisted, it makes a POST request to `/upscan/initiate` or `upscan/v2/initiate`. The service must provide a callbackUrl for asynchronous notification of the outcome of a upload. The callback will be made from inside the MDTP environment. Hence, the callback URL should comprise the MDTP internal callback address and not the public domain address. `upscan/v2/initiate` additionally requires a successRedirect and errorRedirect url. See next section for specifics. 

**Note:** `callbackUrl` must use the `https` protocol.
(Although this rule is relaxed when testing locally with [upscan-stub](https://github.com/hmrc/upscan-stub) rather than [upscan-initiate](https://github.com/hmrc/upscan-initiate).
In this stubbed scenario a `callbackUrl` referring to localhost may still specific `http` as the protocol.)

Session-ID / Request-ID headers will be used to link the file with user's journey.

*Note:* If you are using `[http-verbs](https://github.com/hmrc/http-verbs)` to call Upscan, all the headers will be set automatically
(See: [HttpVerb.scala](https://github.com/hmrc/http-verbs/blob/2807dc65f64009bd7ce1f14b38b356e06dd23512/src/main/scala/uk/gov/hmrc/http/HttpVerb.scala#L53))

The service replies with a pre-filled template for the upload of the file.
The JSON response also contains a globally unique file reference of the upload. This reference can be used by the Upscan service team to view the progress and result of the journey through the different Upscan components. The consuming service can use this reference to correlate the upload request with a successfully uploaded file.


### POST upscan/v2/initiate

The S3 rest api is able to redirect on successful uploads. It does not support redirects on errors however. Version 2 of `upscan/initiate` returns the url [upscan-upload-proxy](https://github.com/hmrc/upscan-upload-proxy) that sits in front of S3 to gracefully handle S3 errors.  

Example `upscan/v2/initiate` request:

```json
{
    "callbackUrl": "https://myservice.com/callback",
    "successRedirect": "https://myservice.com/nextPage",
    "errorRedirect": "https://myservice.com/errorPage",
    "minimumFileSize" : 0,
    "maximumFileSize" : 1024
}
```

#### HTTP Headers:

| Header name|Description|Required|
|--------------|-----------|--------|
| User-Agent | Identifier of the service that calls upscan | yes |
| X-Session-ID | Identifier of the user's session | no  |
| X-Request-ID | Identifier of the user's request | no |

#### Body parameters:

| Parameter name|Description|Required|
|--------------|-----------|--------|
|callbackUrl   |Url that will be called to report the outcome of file checking and upload, including retrieval details if successful. Notification format is detailed further down in this file. Must be https.| yes|
|successRedirect|Url to redirect to after file has been successfully uploaded.|yes|
|errorRedirect|Url to redirect to if error encountered during upload.|yes|
|minimumFileSize|Minimum file size (in Bytes). Default is 0.|no|
|maximumFileSize|Maximum file size (in Bytes). Cannot be greater than 100MB. Default is 100MB.|no|

Example response

```json
{
    "reference": "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "uploadRequest": {
        "href": "https://xxxx/upscan-upload-proxy/bucketName",
        "fields": {
            "Content-Type": "application/xml",
            "acl": "private",
            "key": "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            "policy": "xxxxxxxx==",
            "x-amz-algorithm": "AWS4-HMAC-SHA256",
            "x-amz-credential": "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
            "x-amz-date": "yyyyMMddThhmmssZ",
            "x-amz-meta-callback-url": "https://myservice.com/callback",
            "x-amz-signature": "xxxx",
            "success_action_redirect": "https://myservice.com/nextPage",
            "error_action_redirect": "https://myservice.com/errorPage"
        }
    }
}
```

*Note:* We recommend that the response fields are not hardcoded as these are subject to change 

The `href` in the response is for a proxy that sits in front of S3 to handle error responses.

S3 will return errors in `application/xml` in the following format:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>NoSuchKey</Code>
  <Message>The resource you requested does not exist</Message>
  <Resource>/mybucket/myfoto.jpg</Resource> 
  <RequestId>4442587FB7D0A2F9</RequestId>
</Error>
```

Example [upscan-upload-proxy](https://github.com/hmrc/upscan-upload-proxy) error redirect response:

Redirect Response:

```
HTTP Response Code: 303
Header ("Location" ->  "https://myservice.com/errorPage?errorCode=NoSuchKey&errorMessage=The resource you requested does not exist&errorResource=/mybucket/myfoto.jpg$errorRequestId=4442587FB7D0A2F9")
```

Json Error Response (can not redirect):

```json
{"message":"Bad request"}
```


[[Back to the top]](#top)

### POST upscan/initiate 

Example request:

```json
{
    "callbackUrl": "https://myservice.com/callback",
    "minimumFileSize" : 0,
    "maximumFileSize" : 1024
}
```

#### HTTP Headers:

| Header name|Description|Required|
|--------------|-----------|--------|
| User-Agent | Identifier of the service that calls upscan | yes |
| X-Session-ID | Identifier of the user's session | no  |
| X-Request-ID | Identifier of the user's request | no |

#### Body parameters:

| Parameter name|Description|Required|
|--------------|-----------|--------|
|callbackUrl   |Url that will be called to report the outcome of file checking and upload, including retrieval details if successful. Notification format is detailed further down in this file. Must be https.| yes|
|minimumFileSize|Minimum file size (in Bytes). Default is 0.|no|
|maximumFileSize|Maximum file size (in Bytes). Cannot be greater than 100MB. Default is 100MB.|no|
|successRedirect|Url to redirect to after file has been successfully uploaded.|no|



Example Response:

```json
{
    "reference": "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "uploadRequest": {
        "href": "https://bucketName.s3.eu-west-2.amazonaws.com",
        "fields": {
            "Content-Type": "application/xml",
            "acl": "private",
            "key": "xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            "policy": "xxxxxxxx==",
            "x-amz-algorithm": "AWS4-HMAC-SHA256",
            "x-amz-credential": "ASIAxxxxxxxxx/20180202/eu-west-2/s3/aws4_request",
            "x-amz-date": "yyyyMMddThhmmssZ",
            "x-amz-meta-callback-url": "https://myservice.com/callback",
            "x-amz-signature": "xxxx"
        }
    }
}
```

[[Back to the top]](#top)

### The file upload <a name="service__upload"></a>

In order to upload the file, the following form is sent as the body of a POST request:

```
<form method="POST" href="...value of the href from the response above...">
    <input type="hidden" name="x-amz-algorithm" value="AWS4-HMAC-SHA256">
    ... all the fields returned in "fields" map in the response above ...
    <input type="file" name="file"/> <- form field representing the file to upload
    <input type="submit" value="OK"/>
</form>
```

This POST request can be sent programmatically from backend code, by making an async call using AJAX or the submit of a web form.

Whichever way the form is sent, remember:

- You must use multipart encoding (`multipart/form-data`) NOT `application/x-www-form-urlencoded`. If you use` application/x-www-form-urlencoded`, AWS will return a response from which this error is not clear.
- The 'file' field must be the last field in the submitted form.

[[Back to the top]](#top)

### File upload outcome <a name="service__uoutcome"></a>

If the POST is not successful, the service will return a HTTP error code (4xx, 5xx). The response body will contain XML encoded details of the problem. See the Error handling section for details.

If the POST is successful, the service returns a HTTP 204 response with an empty body.

[[Back to the top]](#top)

### File processing outcome <a name="service__poutcome"></a>

#### Success <a name="service__poutcome__success"></a>

When a file is successfully uploaded it is processed by [upscan-verify](https://github.com/hmrc/upscan-verify) to check for viruses & that it is of an allowed file type.

If these checks pass, the file is made for available for retrieval & the Upscan service will make a POST request to the URL specified as the 'callbackUrl' by the consuming service with the following body:

```json
{
    "reference" : "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "fileStatus" : "READY",
    "downloadUrl" : "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
    "uploadDetails": {
        "uploadTimestamp": "2018-04-24T09:30:00Z",
        "checksum": "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
        "fileName": "test.pdf",
        "fileMimeType": "application/pdf"
    }
}
```

Note the block entitled 'uploadDetails', see the Confluence page 'Upscan & Non-Repudiation Service' in the Platform Services space usage of this information:

- `uploadTimestamp` - The timestamp of the file upload
- `checksum` - The SHA256 hash of the uploaded file
- `fileName` - File name as it was provided by the user
- `fileMimeType` - Detected MIME type of the file. Please note that this refers to actual contents  
of the file, not to the name (if user uploads PDF document named `data.png`, it will be detected as a `application/pdf`) 

[[Back to the top]](#top)

#### Failure <a name="service__poutcome__failure"></a>

The list of failure reasons is as follows:

- `QUARANTINE` - the file has failed virus scanning
- `REJECTED` - the file is not of an allowed file type
- `UNKNOWN` - there is some other problem with the file

These reasons form one of the following JSON responses sent to the callback URL:


```json
{
    "reference" : "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "fileStatus" : "FAILED",
    "failureDetails": {
        "failureReason": "QUARANTINE",
        "message": "e.g. This file has a virus"
    }
}
```

```json
{
    "reference" : "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "fileStatus" : "FAILED",
    "failureDetails": {
        "failureReason": "REJECTED",
        "message": "MIME type $mime is not allowed for service $service-name"
    }
}
```

```json
{
    "reference" : "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "fileStatus" : "FAILED",
    "failureDetails": {
        "failureReason": "UNKNOWN",
        "message": "Something unknown happened"
    }
}
```

You or the Upscan service team can use the unique file reference to find out more using the Upscan observability tools.

[[Back to the top]](#top)

## Whitelisting client services <a name="whitelist"></a>

Any service using Upscan must be whitelisted. Please view the "Upscan & Consuming Services" page of the Upscan documentation in Confluence for the onboarding process. The team are also available on Slack [#team-plat-services](https://hmrcdigital.slack.com/messages/C705QD804).

Consuming services must identify themselves in requests via the `User-Agent` header. If the supplied value is not in Upscan's list of allowed services then the `/initiate` call will fail with a `403` error.

In addition to returning a `403` error, Upscan will log details of the Forbidden request. For example:

```json
{
    "app":"upscan-initiate",
    "message":"Invalid User-Agent: [Some(my-unknown-service-name)].",
    "logger":"application",
    "level":"WARN"
}
```

*Note:* If you are using `[http-verbs](https://github.com/hmrc/http-verbs)` to call Upscan, then the `User-Agent` header will be set automatically.
(See: [HttpVerb.scala](https://github.com/hmrc/http-verbs/blob/2807dc65f64009bd7ce1f14b38b356e06dd23512/src/main/scala/uk/gov/hmrc/http/HttpVerb.scala#L53))

[[Back to the top]](#top)

## Error handling <a name="error"></a>

This document indicates the responses from Upscan components, including error/failure cases.

The actual file upload is to an AWS endpoint and the responses come straight from AWS. These responses are documented here: https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html

[[Back to the top]](#top)

## Design considerations <a name="design"></a>

### Uploading multiple files <a name="design__multiple"></a>
Upscan supports single file uploads. If a consuming service needs to upload multiple files during one user's journey, it must make multiple independent calls to upscan-initiate.

[[Back to the top]](#top)

### Security <a name="design__security"></a>
The callback URL provided by the service will be sent in plain text through the network and will be visible to anyone receiving or inspecting the upload request details.

Because of this, the URL must not:
- Point to a host that is accessible from outside MDTP.
- Contain sensitive data (e.g. user identifiers, session tokens, &c.)

[[Back to the top]](#top)

### File metadata <a name="design__metadata"></a>
Upscan intentionally doesn't allow consuming services to attach metadata or tags to the uploaded file. It is expected that the consuming service will use the globally unique file reference to correlate any file metadata to an successfully uploaded file when it is notified of the successful upload.

Upscan must not be used to route or transfer files between different services on MDTP - it is for files to be uploaded into the HMRC estate.

[[Back to the top]](#top)

## Architecture of the service <a name="architecture"></a>

Please see the Upscan Confluence page for architecture overview and details.

[[Back to the top]](#top)

## Running and maintenance of the service <a name="run"></a>

### Running locally <a name="run__local"></a>

In order to run the service against one of HMRC AWS accounts {labs, live} it's necessary to have an AWS user with the proper role. See [UpScan Accounts/roles](https://github.com/hmrc/aws-users/blob/master/AccountLinks.md) for proper details.

Prerequisites:

- AWS user with correct roles & MFA enabled
- AWS credential configuration set up according to this document [aws-credential-configuration](https://github.com/hmrc/aws-users), with the credentials below:

```
[webops-users]
mfa_serial = arn:aws:iam::638924580364:mfa/YOUR_AWS_USER_NAME
aws_access_key_id = YOUR_ACCESS_KEY_HERE
aws_secret_access_key = YOUR_SECRET_KEY_HERE
output = json
region = eu-west-2

[upscan-labs-engineer]
source_profile = webops-users
role_arn = arn:aws:iam::063874132475:role/RoleServiceUpscanEngineer
mfa_serial = arn:aws:iam::638924580364:mfa/YOUR_AWS_USER_NAME
aws_access_key_id = YOUR_ACCESS_KEY_HERE
aws_secret_access_key = YOUR_SECRET_KEY_HERE
output = json
region = eu-west-2
```
- Python 2.7 installed
- Botocore and awscli python modules installed locally:
  - Linux:

		```
		sudo pip install botocore
		sudo pip install awscli
		```

  - Mac (Mac has issues with pre-installed version of ```six``` as discussed [here](https://github.com/pypa/pip/issues/3165) ):

		```
		sudo pip install botocore --ignore-installed six
		sudo pip install awscli --ignore-installed six
		```

In order to run the app against the lab environment it's necessary to run the following commands:

```
export AWS_S3_BUCKET_INBOUND=name of inbound s3 bucket you would like to use
export AWS_DEFAULT_PROFILE=name of proper profile in ~/.aws/credentials file
./aws-profile sbt
```
These commands will give you an access to SBT shell where you can run the service using 'run' or 'start' commands.

[[Back to the top]](#top)

## Appendix <a name="appendix"></a>
### Quick reference figures <a name="appendix__figures"></a>

| Metric                                                  | Value          | Comments |
| ------------------------------------------------------- | -------------- |----------|
| Expiration of S3 upload pre-signed URL                  | Up to 7 days   | A relatively long period, since we can't control exactly when users will initiate the upload process |
| Expiration of S3 download pre-signed URL (scanned docs) | Up to 1 day    | Upscan is not intended as a storage solution for services |
| Callback request retry time                             | 60 seconds     |          |
| Maximum callback notification retries                   | 30             |          |

[[Back to the top]](#top)

### Related projects, useful links: <a name="appendix__links"></a>

* [upscan-verify](https://github.com/hmrc/upscan-verify) - service responsible for verifying the health of uploaded files
* [upscan-notify](https://github.com/hmrc/upscan-notify) - service responsible for notifying consuming services about the status of uploaded files
* [upscan-infrastructue](https://github.com/hmrc/upscan-infrastructure) - AWS infrastructure provisioning scripts

[[Back to the top]](#top)

#### Testing <a name="appendix__links__testing"></a>
* [upscan-listener](https://github.com/hmrc/upscan-listener) - service used in testing to receive callbacks from `upscan-notify`
* [upscan-stub](https://github.com/hmrc/upscan-stub) - service used locally (via `ServiceManager`) to stub `upscan-initiate`, `upscan-verify`, `upscan-notify` and uploads to AWS S3.
* [upscan-acceptance-tests](https://github.com/hmrc/upscan-acceptance-tests) - end-to-end acceptance tests of the upscan ecosystem
* [upscan-performance-tests](https://github.com/hmrc/upscan-performance-tests) - performance tests of the upscan ecosystem

[[Back to the top]](#top)

#### Slack <a name="appendix__links__slack"></a>
* [#team-plat-services](https://hmrcdigital.slack.com/messages/C705QD804/)
* [#event-upscan](https://hmrcdigital.slack.com/messages/C8XPL559N)

[[Back to the top]](#top)

### License <a name="appendix__license"></a>

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")

[[Back to the top]](#top)
