package org.congocc.templates;

import org.congocc.templates.core.nodes.generated.*;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.parser.Node;
import org.congocc.templates.core.parser.ParseException;
import org.congocc.templates.core.parser.ParsingProblemImpl;
import org.congocc.templates.core.nodes.AssignmentInstruction;

/**
 * A class that visits the AST after the parsing step proper,
 * and makes various checks and adjustments.
 */
class PostParseVisitor extends Node.Visitor {

	private Template template;

	PostParseVisitor(Template template) {
		this.template = template;
	}

	void visit(Template template) {
		TemplateHeaderElement header = template.getHeaderElement();
		if (header != null) visit(header);
		visit(template.getRootTreeNode());
	}

	void visit(AssignmentInstruction node) {
		recurse(node);
		for (Expression target : node.getTargetExpressions()) {
			if (!target.isAssignableTo()) {
				ParsingProblemImpl problem = new ParsingProblemImpl("Cannot assign to expression" + target + " ", target);
				template.addParsingProblem(problem);
			}
		}
	}

	void visit(BlockAssignment node) {
		recurse(node);
		Expression targetExpression = node.getTargetExpression();
		if (!targetExpression.isAssignableTo()) {
			ParsingProblemImpl problem = new ParsingProblemImpl("The expression " + targetExpression + " cannot be assigned to.", targetExpression);
			template.addParsingProblem(problem);
		}
	}

	void visit(Macro node) {
		String macroName = node.getName();
		if (template.declaresVariable(macroName)) {
			ParsingProblemImpl problem = new ParsingProblemImpl("You already have declared a variable (or declared another macro) as " + macroName + ". You cannot reuse the variable name in the same template.", node);
			template.addParsingProblem(problem);
		}
		template.declareVariable(macroName);
		Node parent=node.getParent();
		while (parent != null) {
			parent = parent.getParent();
			if (parent != null && !(parent instanceof Block)) {
				ParsingProblemImpl problem = new ParsingProblemImpl("Macro " + macroName + " is within a " + ((TemplateNode)parent).getDescription() + ". It must be a top-level element.", node);
				template.addParsingProblem(problem);
			}
		}
		template.addMacro(node);
		recurse(node);
	}

	void visit(IteratorBlock node) {
		node.getNestedBlock().declareVariable(node.getIndexName());
		node.getNestedBlock().declareVariable(node.getIndexName() + "_has_next");
		node.getNestedBlock().declareVariable(node.getIndexName() + "_index");
		if (node.getValueVarName() != null) {
			node.getNestedBlock().declareVariable(node.getValueVarName());
			node.getNestedBlock().declareVariable(node.getValueVarName() + "_has_next");
			node.getNestedBlock().declareVariable(node.getValueVarName() + "_index");
		}
		recurse(node);
	}

	void visit(LoopBlock node) {
		node.getNestedBlock().declareVariable("__index");
		recurse(node);
	}

	void visit(BreakInstruction node) {
		recurse(node);
		if (node.firstAncestorOfType(IteratorBlock.class) == null
		    && node.firstAncestorOfType(LoopBlock.class) == null) {
			template.addParsingProblem(new ParsingProblemImpl("The break directive can only be used within a loop.", node));
		}
	}

	void visit(ReturnInstruction node) {
		recurse(node);
		Macro macro = node.firstAncestorOfType(Macro.class);
		if (macro == null) {
       		template.addParsingProblem(new ParsingProblemImpl("The return directive can only be used inside a function or macro.", node));
		} else {
			if (!macro.isFunction() && node.size() > 2) {
				template.addParsingProblem(new ParsingProblemImpl("Can only return a value from a function, not a macro", node));
			}
			else if (macro.isFunction() && node.size() ==2) {
				template.addParsingProblem(new ParsingProblemImpl("A function must return a value.", node));
			}
		}
	}

	// This is a VERY important point in the code,
	// where variables declared via #var are
	// "declared" in their respective scopes.
	void visit(VarDirective node) {
        Block parent = (Block) node.getParent();
       	for (String key : node.getVariables().keySet()) {
       		if (parent == null) {
       			template.declareVariable(key);
       		} else {
       			if (parent.declaresVariable(key)) {
       				String msg = "The variable " + key + " has already been declared in this block.";
       				template.addParsingProblem(new ParsingProblemImpl(msg, node));
       			}
       			parent.declareVariable(key);
       		}
       	}
	}

	void visit(StringLiteral node) {
		if (!node.isRaw()) {
			try {
				node.checkInterpolation();
			} catch (ParseException pe) {
				String msg = "Error in string " + node.getLocation();
				msg += "\n" + pe.getMessage();
				template.addParsingProblem(new ParsingProblemImpl(msg, node));
			}
		}
	}

	void visit(ImportDeclaration node) {
		String namespaceName = node.getNamespace();
		if (template.declaresVariable(namespaceName)) {
			String msg = "The variable "+namespaceName + " is already declared and should not be used as a namespace name to import.";
			template.addParsingProblem(new ParsingProblemImpl(msg, node));
		}
		template.declareVariable(namespaceName);
		recurse(node);
	}
}
