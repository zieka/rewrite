package org.openrewrite.java

import org.junit.jupiter.api.Test
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

@Suppress("EmptyTryBlock")
class PreventZipSlipTest : RewriteTest {
    override fun defaults(spec: RecipeSpec) {
        spec.expectedCyclesThatMakeChanges(1).cycles(1)
        spec.recipe(PreventZipSlip())
    }

    @Test
    fun preventZipSlip() = rewriteRun(
        java(
            """
                import java.io.*;
                import java.util.zip.*;
                
                class Test {
                    File destinationDir;
                    ZipFile zip;
                    
                    void test() throws Exception {
                        ZipEntry entry = zip.getEntry("entry");
                        File f = new File(destinationDir, entry.getName());
                        try(FileOutputStream fos = new FileOutputStream(f)) {
                        }
                    }
                }
            """,
            """
                import java.io.*;
                import java.util.zip.*;
                
                class Test {
                    File destinationDir;
                    ZipFile zip;
                    
                    void test() throws Exception {
                        ZipEntry entry = zip.getEntry("entry");
                        File f = new File(destinationDir, entry.getName());
                        if (!f.toPath().normalize().startsWith(destinationDir.toPath())) {
                            throw new Exception("Bad zip entry");
                        }
                        try(FileOutputStream fos = new FileOutputStream(f)) {
                        }
                    }
                }
            """
        )
    )
}
