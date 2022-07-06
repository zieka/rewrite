package org.openrewrite.hcl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.openrewrite.hcl.tree.Hcl
import org.openrewrite.test.RewriteTest

class JsonPatchMatcherTest : RewriteTest {

    fun anyMatch(hcl: Hcl, matcher: JsonPathMatcher): Boolean {
        var matches = false
        object : HclVisitor<Int>() {
            override fun visitBlock(block: Hcl.Block, p: Int): Hcl {
                val b = super.visitBlock(block, p)
                matches = matcher.matches(cursor) || matches
                return b
            }
        }.visit(hcl, 0)
        return matches
    }

    @Test
    fun match() = rewriteRun(
        hcl(
            """
                provider "azurerm" {
                  features {
                    key_vault {
                      purge_soft_delete_on_destroy = true
                    }
                  }
                }
            """
        ) { spec ->
            spec.beforeRecipe { configFile ->
                assertThat(anyMatch(configFile, JsonPathMatcher("$.provider.features.key_vault"))).isTrue
                assertThat(anyMatch(configFile, JsonPathMatcher("$.provider.features.dne"))).isFalse
            }
        }
    )
}
