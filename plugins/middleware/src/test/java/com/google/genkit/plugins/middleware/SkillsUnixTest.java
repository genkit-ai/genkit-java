/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.genkit.plugins.middleware;

import static com.google.genkit.plugins.middleware.SkillsTestSupport.findTool;
import static com.google.genkit.plugins.middleware.SkillsTestSupport.mustHooks;
import static com.google.genkit.plugins.middleware.SkillsTestSupport.opts;
import static com.google.genkit.plugins.middleware.SkillsTestSupport.runRaw;
import static com.google.genkit.plugins.middleware.SkillsTestSupport.setupSkillsDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genkit.ai.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Skills tests that need a named pipe, which only POSIX systems provide. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SkillsUnixTest {

  @TempDir Path tmp;

  /** Creates a named pipe, skipping the test when the host cannot. */
  private static void mkfifo(Path p) throws IOException, InterruptedException {
    Process proc;
    try {
      proc = new ProcessBuilder("mkfifo", p.toString()).redirectErrorStream(true).start();
    } catch (IOException e) {
      assumeTrue(false, "cannot create a FIFO here: " + e.getMessage());
      return;
    }
    assumeTrue(proc.waitFor() == 0, "cannot create a FIFO here");
  }

  /** The outcome of a call that must not block. */
  private interface Call {
    void run() throws Exception;
  }

  /**
   * Runs fn on another thread and fails the test if it does not return within the deadline. Opening
   * a named pipe blocks forever, so a regression here must fail rather than hang the suite. Returns
   * the exception fn threw, or null.
   */
  private static Throwable mustNotBlock(String what, Call fn) throws InterruptedException {
    ExecutorService exec =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "must-not-block");
              t.setDaemon(true);
              return t;
            });
    try {
      Future<?> f =
          exec.submit(
              () -> {
                fn.run();
                return null;
              });
      f.get(10, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (TimeoutException e) {
      fail(what + " blocked on a named pipe");
      return null;
    } finally {
      exec.shutdownNow();
    }
  }

  // Opening a named pipe blocks until a writer appears, so the type is checked before the open.
  @Test
  void skillsSkipsFifoSkillMd() throws Exception {
    Path skillsDir = tmp.resolve("skills");
    Path fifoDir = Files.createDirectories(skillsDir.resolve("fifo"));
    mkfifo(fifoDir.resolve("SKILL.md"));

    mustNotBlock(
        "scanSkills",
        () -> {
          Map<String, Skills.SkillInfo> info =
              Skills.scanSkills(new Skills.DiskStore(), List.of(skillsDir.toString()), true, null);
          assertTrue(info.isEmpty(), "scanned " + info.keySet() + ", want none");
        });

    // readSkillFile is the second line of defence, for a file swapped after the scan.
    Throwable err =
        mustNotBlock("readSkillFile", () -> Skills.readSkillFile(fifoDir.resolve("SKILL.md")));
    assertNotNull(err, "readSkillFile accepted a FIFO");
  }

  // The bundled-resource reader confines the path but must also police the file type.
  @Test
  void skillsResourceReadRefusesFifo() throws Exception {
    Path skillsDir = setupSkillsDir(tmp);
    Path py = skillsDir.resolve("python");
    Files.createDirectories(py.resolve("scripts"));
    mkfifo(py.resolve("scripts").resolve("pipe"));

    Skills.Options o = opts(skillsDir);
    o.allowResourceAccess = true;
    Skills h = mustHooks(new Skills(o));
    Tool<?, ?> read = findTool(h, Skills.SKILL_RESOURCE_TOOL_NAME);
    assertNotNull(read, "read_skill_file was not registered");

    Throwable err =
        mustNotBlock(
            "read_skill_file",
            () -> runRaw(read, Map.of("skillName", "python", "filePath", "scripts/pipe")));
    assertNotNull(err, "read_skill_file accepted a FIFO");

    // The listing must not offer it either.
    String got =
        Skills.listSkillResources(
            new Skills.DiskStore(), py.toString(), Skills.SKILL_RESOURCE_TOOL_NAME);
    assertFalse(got.contains("pipe"), "the resource listing advertises a FIFO: " + got);
  }

  // A named pipe swapped in after the type check must be refused, not opened.
  @Test
  void readCheckedRefusesSwappedFifo() throws Exception {
    Path checked = tmp.resolve("checked");
    Files.writeString(checked, "text");
    BasicFileAttributes st =
        Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Path pipe = tmp.resolve("pipe");
    mkfifo(pipe);

    Throwable err =
        mustNotBlock("readChecked", () -> Skills.readChecked(pipe, st, checked.toString()));
    assertNotNull(err, "readChecked read a named pipe in place of the file it checked");
  }
}
