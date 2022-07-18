/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.cobol.internal;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.openrewrite.FileAttributes;
import org.openrewrite.cobol.internal.grammar.CobolBaseVisitor;
import org.openrewrite.cobol.internal.grammar.CobolParser;
import org.openrewrite.cobol.tree.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Markers;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.openrewrite.Tree.randomId;

public class CobolParserVisitor extends CobolBaseVisitor<Cobol> {
    private final Path path;

    @Nullable
    private final FileAttributes fileAttributes;

    private final String source;
    private final Charset charset;
    private final boolean charsetBomMarked;

    private int cursor = 0;

    public CobolParserVisitor(Path path, @Nullable FileAttributes fileAttributes,
                              String source, Charset charset, boolean charsetBomMarked) {
        this.path = path;
        this.fileAttributes = fileAttributes;
        this.source = source;
        this.charset = charset;
        this.charsetBomMarked = charsetBomMarked;
    }

    private int positionInParent(ParseTree n) {
        ParseTree parent = n.getParent();
        int pos;
        for (pos = 0; pos < parent.getChildCount(); pos++) {
            if (parent.getChild(pos) == n) {
                break;
            }
        }
        assert pos < parent.getChildCount();
        return pos;
    }

    @Override
    public Cobol.CompilationUnit visitStartRule(CobolParser.StartRuleContext ctx) {
        return visitCompilationUnit(ctx.compilationUnit()).withEof(ctx.EOF().getText());
    }

    @Override
    public Cobol.CompilationUnit visitCompilationUnit(CobolParser.CompilationUnitContext ctx) {
        Space prefix = prefix(ctx);
        List<CobolParser.ProgramUnitContext> puCtxs = ctx.programUnit();
        List<CobolRightPadded<Cobol.ProgramUnit>> programUnits = new ArrayList<>(puCtxs.size());
        for (CobolParser.ProgramUnitContext puCtx : puCtxs) {
            programUnits.add(CobolRightPadded.build((Cobol.ProgramUnit) visitProgramUnit(puCtx)));
        }

        return new Cobol.CompilationUnit(
                randomId(),
                path,
                fileAttributes,
                prefix,
                Markers.EMPTY,
                charset.name(),
                charsetBomMarked,
                null,
                programUnits,
                source.substring(cursor)
        );
    }

    @Override
    public Cobol visitProgramUnit(CobolParser.ProgramUnitContext ctx) {
        return new Cobol.ProgramUnit(
                randomId(),
                prefix(ctx),
                Markers.EMPTY,
                (Cobol.IdentificationDivision) visitIdentificationDivision(ctx.identificationDivision()),
                ctx.procedureDivision() == null ? null : (Cobol.ProcedureDivision) visitProcedureDivision(ctx.procedureDivision())
        );
    }

    @Override
    public Cobol visitProcedureDivision(CobolParser.ProcedureDivisionContext ctx) {
        if (ctx.procedureDivisionUsingClause() != null || ctx.procedureDivisionGivingClause() != null ||
                ctx.procedureDeclaratives() != null) {
            throw new UnsupportedOperationException("Implement me");
        }
        return new Cobol.ProcedureDivision(
                randomId(),
                sourceBefore(ctx.PROCEDURE().getText()),
                Markers.EMPTY,
                ctx.PROCEDURE().getText(),
                padLeft(ctx.DIVISION()),
                padLeft(sourceBefore("."), visitProcedureDivisionBody(ctx.procedureDivisionBody()))
        );
    }

    @Override
    public Cobol.ProcedureDivisionBody visitProcedureDivisionBody(CobolParser.ProcedureDivisionBodyContext ctx) {
        if (ctx.procedureSection() != null && !ctx.procedureSection().isEmpty()) {
            throw new UnsupportedOperationException("Implement me");
        }
        return new Cobol.ProcedureDivisionBody(
                randomId(),
                prefix(ctx),
                Markers.EMPTY,
                visitParagraphs(ctx.paragraphs())
        );
    }

    @Override
    public Cobol.Paragraphs visitParagraphs(CobolParser.ParagraphsContext ctx) {
        Space prefix = prefix(ctx);
        if (ctx.paragraph() != null && !ctx.paragraph().isEmpty()) {
            throw new UnsupportedOperationException("Implement me");
        }
        List<CobolRightPadded<Cobol.Sentence>> sentences = new ArrayList<>(ctx.sentence().size());
        for (int i = 0; i < ctx.sentence().size(); i++) {
            Cobol.Sentence unpadded = visitSentence(ctx.sentence(i));
            sentences.add(padRight(unpadded, sourceBefore(".")));
        }
        return new Cobol.Paragraphs(
                randomId(),
                prefix,
                Markers.EMPTY,
                CobolContainer.build(sentences)
        );
    }

    @Override
    public Cobol.Sentence visitSentence(CobolParser.SentenceContext ctx) {
        Space prefix = prefix(ctx);
        List<Statement> statements = new ArrayList<>(ctx.statement().size());
        for (int i = 0; i < ctx.statement().size(); i++) {
            statements.add((Statement) visit(ctx.statement(i)));
        }
        return new Cobol.Sentence(
                randomId(),
                prefix,
                Markers.EMPTY,
                statements
        );
    }

    @Override
    public Cobol visitStopStatement(CobolParser.StopStatementContext ctx) {
        if (ctx.literal() != null || ctx.stopStatementGiving() != null) {
            throw new UnsupportedOperationException("Implement me");
        }
        return new Cobol.Stop(
                randomId(),
                sourceBefore(ctx.STOP().getText()),
                Markers.EMPTY,
                ctx.STOP().getText(),
                ctx.RUN() == null ? null : padLeft(sourceBefore(ctx.RUN().getText()), ctx.RUN().getText()),
                null
        );
    }

    @Override
    public Cobol visitDisplayStatement(CobolParser.DisplayStatementContext ctx) {
        if (ctx.displayAt() != null || ctx.displayUpon() != null || ctx.displayWith() != null ||
                ctx.onExceptionClause() != null || ctx.notOnExceptionClause() != null ||
                ctx.END_DISPLAY() != null) {
            throw new UnsupportedOperationException("Implement me");
        }

        Space prefix = sourceBefore(ctx.DISPLAY().getText());
        List<Name> operands = new ArrayList<>(ctx.displayOperand().size());
        for (int i = 0; i < ctx.displayOperand().size(); i++) {
            operands.add((Name) visit(ctx.displayOperand(i)));
        }

        return new Cobol.Display(
                randomId(),
                prefix,
                Markers.EMPTY,
                ctx.DISPLAY().getText(),
                operands
        );
    }

    @Override
    public Cobol visitIdentificationDivision(CobolParser.IdentificationDivisionContext ctx) {
        if (ctx.identificationDivisionBody() != null && ctx.identificationDivisionBody().size() != 0) {
            throw new UnsupportedOperationException("Implement me");
        }
        String id = (ctx.IDENTIFICATION() == null ? ctx.ID() : ctx.IDENTIFICATION()).getText();
        return new Cobol.IdentificationDivision(
                randomId(),
                sourceBefore(id),
                Markers.EMPTY,
                id,
                padLeft(ctx.DIVISION()),
                padLeft(sourceBefore("."), (Cobol.ProgramIdParagraph) visitProgramIdParagraph(ctx.programIdParagraph()))
        );
    }

    @Override
    public Cobol visitLiteral(CobolParser.LiteralContext ctx) {
        return new Cobol.Literal(
                randomId(),
                sourceBefore(ctx.getText()),
                Markers.EMPTY,
                ctx.getText(), // TODO extract literal values from various literal types
                ctx.getText()
        );
    }

    @Override
    public Cobol visitProgramIdParagraph(CobolParser.ProgramIdParagraphContext ctx) {
        return new Cobol.ProgramIdParagraph(
                randomId(),
                sourceBefore(ctx.PROGRAM_ID().getText()),
                Markers.EMPTY,
                ctx.PROGRAM_ID().getText(),
                padLeft(
                        sourceBefore("."),
                        ctx.programName().NONNUMERICLITERAL() == null ?
                                new Cobol.Identifier(randomId(),
                                        sourceBefore(ctx.programName().getText()), Markers.EMPTY,
                                        ctx.programName().getText()) :
                                new Cobol.Literal(randomId(),
                                        sourceBefore(ctx.programName().getText()), Markers.EMPTY,
                                        ctx.programName().getText(), ctx.programName().getText())
                )
        );
    }

    @Nullable
    private <C extends ParserRuleContext, T> T convert(@Nullable C ctx, BiFunction<C, Space, T> conversion) {
        if (ctx == null) {
            return null;
        }
        return conversion.apply(ctx, prefix(ctx));
    }

    private <T> T convert(TerminalNode node, BiFunction<TerminalNode, Space, T> conversion) {
        return conversion.apply(node, prefix((Token) node));
    }

    private Space prefix(ParserRuleContext ctx) {
        return prefix(ctx.getStart());
    }

    private Space prefix(Token token) {
        int start = token.getStartIndex();
        if (start < cursor) {
            return Space.format("");
        }
        String prefix = source.substring(cursor, start);
        cursor = start;
        return Space.format(prefix);
    }

    private Space sourceBefore(String untilDelim) {
        return sourceBefore(untilDelim, null);
    }

    /**
     * @return Source from <code>cursor</code> to next occurrence of <code>untilDelim</code>,
     * and if not found in the remaining source, the empty String. If <code>stop</code> is reached before
     * <code>untilDelim</code> return the empty String.
     */
    private Space sourceBefore(String untilDelim, @Nullable Character stop) {
        int delimIndex = positionOfNext(untilDelim, stop);
        if (delimIndex < 0) {
            return Space.EMPTY; // unable to find this delimiter
        }

        String prefix = source.substring(cursor, delimIndex);
        cursor += prefix.length() + untilDelim.length(); // advance past the delimiter
        return Space.format(prefix);
    }

    private <T> CobolRightPadded<T> padRight(T tree, Space right) {
        return new CobolRightPadded<>(tree, right, Markers.EMPTY);
    }

    private <T> CobolLeftPadded<T> padLeft(Space left, T tree) {
        return new CobolLeftPadded<>(left, tree, Markers.EMPTY);
    }

    private <T> CobolLeftPadded<String> padLeft(@Nullable TerminalNode terminalNode) {
        if (terminalNode == null) {
            //noinspection ConstantConditions
            return null;
        }
        return padLeft(sourceBefore(terminalNode.getText()), terminalNode.getText());
    }

    private int positionOfNext(String untilDelim, @Nullable Character stop) {
        int delimIndex = cursor;
        for (; delimIndex < source.length() - untilDelim.length() + 1; delimIndex++) {
            if (stop != null && source.charAt(delimIndex) == stop)
                return -1; // reached stop word before finding the delimiter

            if (source.startsWith(untilDelim, delimIndex)) {
                break; // found it!
            }
        }

        return delimIndex > source.length() - untilDelim.length() ? -1 : delimIndex;
    }

    @Nullable
    private Token previousToken(ParseTree n) {
        ParseTree parent = n.getParent();
        if (n.getParent() == null) {
            return null;
        }
        int pos = positionInParent(n);
        if (pos == 0) {
            return previousToken(parent);
        } else {
            return lastToken(parent.getChild(pos - 1));
        }
    }

    private Token lastToken(ParseTree n) {
        if (n instanceof TerminalNode) {
            return ((TerminalNode) n).getSymbol();
        } else {
            return ((ParserRuleContext) n).stop;
        }
    }
}
