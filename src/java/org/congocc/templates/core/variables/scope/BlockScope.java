package org.congocc.templates.core.variables.scope;

import java.util.HashMap;
import org.congocc.templates.core.nodes.generated.Block;
import org.congocc.templates.*;

public class BlockScope extends HashMap<String,Object> implements Scope {
	
	private Block block;
	private Scope enclosingScope;
	
	public BlockScope(Block block, Scope enclosingScope) {
		this.block = block;
		this.enclosingScope = enclosingScope;
	}

	public Scope getEnclosingScope() {
		return enclosingScope;
	}

	public Template getTemplate() {
		return block.getTemplate();
	}
	
	public Object put(String key, Object tm) {
		if (!definesVariable(key)) {
			throw new IllegalArgumentException("The variable " + key + " is not declared here.");
		}
		return super.put(key, tm);
	}

	protected void putUnconditionally(String key, Object var) {
		super.put(key, var);
	}

	public Block getBlock() {
		return block;
	}
	
	public boolean definesVariable(String name) {
		return getBlock().declaresVariable(name);
	}

    public Object remove(String key) {
        return super.remove(key);
    }

    public void clear() {
        super.clear();
    }

	public boolean isTemplateNamespace() {
		return block.isTemplateRoot();
	}
}

