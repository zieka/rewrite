package org.openrewrite.java;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.java.dataflow.LocalFlowSpec;
import org.openrewrite.java.dataflow.analysis.SinkFlow;
import org.openrewrite.java.search.FindTypes;
import org.openrewrite.java.tree.J;

import static java.util.Objects.requireNonNull;

public class PreventZipSlip extends Recipe {
    private static final MethodMatcher FILE_CREATE = new MethodMatcher("java.io.File <constructor>(java.io.File, java.lang.String)");
    private static final MethodMatcher CREATE_OUTPUT_STREAM = new MethodMatcher("java.io.FileOutputStream <constructor>(java.io.File)");

    private static final LocalFlowSpec<J.NewClass, J.NewClass> ZIP_SLIP = new LocalFlowSpec<J.NewClass, J.NewClass>() {
        @Override
        public boolean isSource(J.NewClass source, Cursor cursor) {
            return FILE_CREATE.matches(source) && FindTypes.find(requireNonNull(source.getArguments()).get(1),
                    "java.util.zip.ZipEntry").isEmpty();
        }

        @Override
        public boolean isSink(J.NewClass sink, Cursor cursor) {
            return CREATE_OUTPUT_STREAM.matches(sink);
        }
    };

    @Override
    public String getDisplayName() {
        return "Prevent zip slip";
    }

    @Override
    public String getDescription() {
        return "Extracting files from a malicious archive without validating that the " +
                "destination file path is within the destination directory can cause files outside the destination directory to be overwritten.";
    }

    @Override
    protected JavaVisitor<ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            private final JavaTemplate noZipSlip = JavaTemplate.builder(this::getCursor, "" +
                    "if (!#{any(java.io.File)}.toPath().normalize().startsWith(#{any(java.io.File)}.toPath())) {\n" +
                    "    throw new Exception(\"Bad zip entry\");\n" +
                    "}").build();

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext executionContext) {
                J.Block b = super.visitBlock(block, executionContext);
                SinkFlow<J.NewClass, ?> zipSlip = getCursor().getMessage("zipSlip");
                if (zipSlip != null) {
                    J.VariableDeclarations file = zipSlip.getSourceCursor().firstEnclosing(J.VariableDeclarations.class);
                    if (file != null) {
                        return b.withTemplate(noZipSlip, file.getCoordinates().after(),
                                file.getVariables().get(0).getName(),
                                requireNonNull(zipSlip.getSource().getArguments()).get(0)
                        );
                    }
                }
                return b;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = super.visitNewClass(newClass, ctx);
                SinkFlow<J.NewClass, J.NewClass> zipSlips = dataflow().findSinks(ZIP_SLIP);
                if (zipSlips.isNotEmpty()) {
                    getCursor().putMessageOnFirstEnclosing(J.Block.class, "zipSlip", zipSlips);
                }
                return n;
            }
        };
    }
}
