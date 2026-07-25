# S3 GET & POST (PUT) API Guide (Angular Reference)

This document serves as a reference for reading (GET) and writing/uploading (PUT/POST) files to Amazon S3 using backend pre-signed URLs (based on the Angular **InSync4** implementation).

---

## 📌 Configured Constants

From `dashboard.component.ts`:

```typescript
const S3_BUCKET_NAME = 'prod-web9-s3-depl';
const S3_FILE_PATH = 'global/module_videoLink/video_links.json';
const DEAWS_ENDPOINT = 'sims-services/digitalsims/?r=api/v1/DeAws/Mgmt';
```

---

## 🚀 1. S3 GET API (Reading/Fetching Data from S3)

To read a file (such as `video_links.json`) from S3:

### Step 1: Get Pre-Signed Read URL from Backend
Call the backend API endpoint (`DEAWS_ENDPOINT`) with `fileActivityType: 'get'`.

**Request Payload:**
```json
{
  "deAwsParams": {
    "actionType": "getPresignedS3Url",
    "actionData": [
      {
        "bucketName": "prod-web9-s3-depl",
        "filePathAndName": "global/module_videoLink/video_links.json",
        "fileActivityType": "get"
      }
    ]
  }
}
```

**Response Format:**
```json
{
  "collection": {
    "response": {
      "status": "SUCCESS",
      "data": [
        {
          "signedUrl": "https://prod-web9-s3-depl.s3.ap-south-1.amazonaws.com/global/module_videoLink/video_links.json?X-Amz-Algorithm=..."
        }
      ]
    }
  }
}
```

### Step 2: Fetch File Content from S3 Signed URL
Using `S3UploadService` or standard `fetch()`:

```typescript
// Example using S3UploadService in DashboardComponent
async readS3VideoLinks(): Promise<any> {
  const reqPayload = {
    deAwsParams: {
      actionType: 'getPresignedS3Url',
      actionData: [
        {
          bucketName: S3_BUCKET_NAME,
          filePathAndName: S3_FILE_PATH,
          fileActivityType: 'get'
        }
      ]
    }
  };

  try {
    // 1. Get presigned GET URL from backend
    const resData: any = await firstValueFrom(
      this.s3Service.getUploadUrlS3(DEAWS_ENDPOINT, reqPayload)
    );

    const signedUrl = resData[0]?.signedUrl;
    if (!signedUrl) throw new Error('Signed URL not returned');

    // 2. Fetch content directly from S3
    const response = await fetch(signedUrl);
    if (!response.ok) throw new Error(`HTTP Error ${response.status}`);

    const videoLinksJson = await response.json();
    console.log('Fetched Video Links from S3:', videoLinksJson);
    return videoLinksJson;
  } catch (error) {
    console.error('Failed to read video_links.json from S3:', error);
    return null;
  }
}
```

---

## 📤 2. S3 POST / PUT API (Writing/Uploading Data to S3)

To upload or update a file (such as `video_links.json` or any document/media file) on S3:

### Step 1: Request Pre-Signed Upload (PUT) URL from Backend

**Request Payload:**
```json
{
  "deAwsParams": {
    "actionType": "getPresignedS3Url",
    "actionData": [
      {
        "bucketName": "prod-web9-s3-depl",
        "filePathAndName": "global/module_videoLink/video_links.json",
        "fileActivityType": "put"
      }
    ]
  }
}
```

### Step 2: Upload Content to S3 via HTTP PUT

#### Option A: Upload JSON Content / Raw Text
```typescript
// Example uploading JSON string or HTML data
async saveS3VideoLinks(jsonData: any): Promise<boolean> {
  const jsonContent = JSON.stringify(jsonData, null, 2);

  const reqPayload = {
    deAwsParams: {
      actionType: 'getPresignedS3Url',
      actionData: [
        {
          bucketName: S3_BUCKET_NAME,
          filePathAndName: S3_FILE_PATH,
          fileActivityType: 'put'
        }
      ]
    }
  };

  return new Promise((resolve) => {
    this.s3Service.uploadHtmlToS3(jsonContent, DEAWS_ENDPOINT, reqPayload).subscribe({
      next: (res: any) => {
        if (res?.s3Status) {
          console.log('Successfully saved video_links.json to S3');
          resolve(true);
        } else {
          console.error('S3 Upload Failed:', res?.error);
          resolve(false);
        }
      },
      error: (err) => {
        console.error('Upload Error:', err);
        resolve(false);
      }
    });
  });
}
```

#### Option B: Upload a File (Image, PDF, Document)
```typescript
// Example uploading a File object (using S3UploadService.uploadOnS3)
uploadFileToS3(file: File, customPath?: string) {
  const targetPath = customPath || S3_FILE_PATH;

  const reqPayload = {
    deAwsParams: {
      actionType: 'getPresignedS3Url',
      actionData: [
        {
          bucketName: S3_BUCKET_NAME,
          filePathAndName: targetPath,
          fileActivityType: 'put'
        }
      ]
    }
  };

  this.s3Service.uploadOnS3(file, DEAWS_ENDPOINT, reqPayload).subscribe({
    next: (res) => {
      if (res?.s3Status) {
        console.log('File uploaded successfully!', res.uploadedSignedUrl);
      } else {
        console.error('Upload failed:', res.error);
      }
    },
    error: (err) => console.error('Upload Error:', err)
  });
}
```

---

## 🛠 Complete Component Integration Template

```typescript
import { Component, OnInit } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { S3UploadService } from '../../shared/de-services/s3-upload.service';

const S3_BUCKET_NAME = 'prod-web9-s3-depl';
const S3_FILE_PATH = 'global/module_videoLink/video_links.json';
const DEAWS_ENDPOINT = 'sims-services/digitalsims/?r=api/v1/DeAws/Mgmt';

@Component({
  selector: 'app-dashboard',
  // ...
})
export class DashboardComponent implements OnInit {

  constructor(
    private s3Service: S3UploadService
    // ...
  ) {}

  ngOnInit(): void {
    // Read video links on initialization
    this.loadVideoLinks();
  }

  /** GET: Load video links JSON from S3 */
  async loadVideoLinks(): Promise<void> {
    const payload = {
      deAwsParams: {
        actionType: 'getPresignedS3Url',
        actionData: [
          {
            bucketName: S3_BUCKET_NAME,
            filePathAndName: S3_FILE_PATH,
            fileActivityType: 'get'
          }
        ]
      }
    };

    try {
      const res = await firstValueFrom(
        this.s3Service.getUploadUrlS3(DEAWS_ENDPOINT, payload)
      );
      const signedUrl = res[0]?.signedUrl;

      if (signedUrl) {
        const response = await fetch(signedUrl);
        const data = await response.json();
        console.log('Loaded Video Links:', data);
      }
    } catch (err) {
      console.error('Error fetching S3 video links:', err);
    }
  }

  /** POST/PUT: Save video links JSON back to S3 */
  saveVideoLinks(updatedVideoLinks: any): void {
    const jsonString = JSON.stringify(updatedVideoLinks, null, 2);
    const payload = {
      deAwsParams: {
        actionType: 'getPresignedS3Url',
        actionData: [
          {
            bucketName: S3_BUCKET_NAME,
            filePathAndName: S3_FILE_PATH,
            fileActivityType: 'put'
          }
        ]
      }
    };

    this.s3Service.uploadHtmlToS3(jsonString, DEAWS_ENDPOINT, payload).subscribe({
      next: (res) => {
        if (res?.s3Status) {
          console.log('Video links saved to S3 successfully!');
        }
      },
      error: (err) => console.error('Error saving video links to S3:', err)
    });
  }
}
```

---

## 📑 Summary Table

| Operation | Action Type | `fileActivityType` | Service Method Used | Direct Endpoint |
|---|---|---|---|---|
| **Read (GET)** | `getPresignedS3Url` | `'get'` | `s3Service.getUploadUrlS3(...)` + `fetch(url)` | S3 Pre-Signed GET URL |
| **Write/Upload (PUT/POST)** | `getPresignedS3Url` | `'put'` | `s3Service.uploadHtmlToS3(...)` or `s3Service.uploadOnS3(...)` | S3 Pre-Signed PUT URL |
