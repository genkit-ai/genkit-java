#!/bin/bash
#
# Copyright 2025 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0

set -e

# Check for required environment variables
if [ -z "$GEMINI_API_KEY" ]; then
    echo "Error: GEMINI_API_KEY environment variable is not set"
    echo "Please set it with: export GEMINI_API_KEY=your-api-key"
    exit 1
fi

if [ -z "$GCLOUD_PROJECT" ] && [ -z "$GOOGLE_CLOUD_PROJECT" ]; then
    echo "Error: GCLOUD_PROJECT or GOOGLE_CLOUD_PROJECT environment variable is not set"
    echo "Please set it with: export GCLOUD_PROJECT=your-project-id"
    exit 1
fi

#run
mvn compile exec:java -Dexec.mainClass="com.google.genkit.samples.firebase.FirestoreRAGSample"
