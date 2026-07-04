#!/bin/bash

# Genkit Sample Runner
# This script runs the sample application

echo "Building and running the sample..."
mvn clean compile exec:java
