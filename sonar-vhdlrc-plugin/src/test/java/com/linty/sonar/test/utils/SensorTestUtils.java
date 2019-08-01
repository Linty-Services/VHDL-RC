package com.linty.sonar.test.utils;

import com.linty.sonar.plugins.vhdlrc.Vhdl;
import java.nio.file.Path;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SensorTestUtils {
   
  public static InputFile getInputFile(Path baseDir, String relativeFilePath, String module) {
    return new TestInputFileBuilder(module, relativeFilePath)
      .setModuleBaseDir(baseDir)
      .setLanguage(Vhdl.KEY)
      .setCharset(UTF_8)
      .build();     
  }
  


}
