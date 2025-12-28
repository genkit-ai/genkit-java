#!/bin/bash

# Genkit Azure AI Foundry Sample Runner
# This script runs the Azure AI Foundry sample application

echo "Building and running Azure AI Foundry sample..."
mvn clean compile exec:java
