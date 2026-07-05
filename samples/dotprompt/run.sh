#!/bin/bash
# Run script for Genkit DotPrompt Sample
cd "$(dirname "$0")"
# Compile before running so the sample's classes always match the installed Genkit libraries.
# (Running exec:java against stale target/classes causes NoSuchMethodError after a library rebuild.)
mvn compile exec:java
