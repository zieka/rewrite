package org.openrewrite.hcl.tree;

import java.util.List;

public interface BodyContentContainer extends Hcl {

    List<BodyContent> getBody();

    BodyContentContainer withBody(List<BodyContent> body);
}
