/*******************************************************************************
 * Copyright (c) 2013 Stefan Seelmann and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Stefan Seelmann - initial API and implementation
 *     Mark Nunberg - Added nested lists
 *******************************************************************************/

package org.eclipse.mylyn.internal.wikitext.markdown.core.block;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.mylyn.wikitext.core.parser.Attributes;
import org.eclipse.mylyn.wikitext.core.parser.DocumentBuilder.BlockType;
import org.eclipse.mylyn.wikitext.core.parser.markup.Block;

/**
 * Markdown lists.
 * 
 * @author Stefan Seelmann
 */
public class ListBlock extends NestableBlock {

	private static final Pattern itemStartPattern = Pattern.compile("( *)(?:([\\*\\+\\-])|([0-9]+\\.))\\s+(.+?)"); //$NON-NLS-1$

	private static final Pattern leadingSpaces = Pattern.compile("^( *)"); //$NON-NLS-1$

	private int blockLineCount = 0;

	private int wsLevel = -1;

	private int contentLevel = -1;

	private boolean wasEmpty = false;

	private Block nestedBlock = null;

	@Override
	public boolean canStart(String line, int lineOffset) {
		Matcher matcher = itemStartPattern.matcher(line.substring(lineOffset));
		return matcher.matches();
	}

	@Override
	public NestableBlock clone() {
		ListBlock cloned = (ListBlock) super.clone();
		cloned.wsLevel = -1;
		cloned.blockLineCount = 0;
		cloned.nestedBlock = null;
		cloned.wasEmpty = false;
		return cloned;
	}

	private static class ChildStatus {
		boolean closed = false;

		int offset;

		ChildStatus(Block block, int newOffset) {
			if (block.isClosed()) {
				closed = true;
				offset = -1;
			} else {
				closed = false;
				offset = newOffset;
			}
		}
	}

	private ChildStatus dispatchToChild(String line, int offset) {
		int newOffset = nestedBlock.processLine(line, offset);
		ChildStatus ret = new ChildStatus(nestedBlock, newOffset);
		if (nestedBlock.isClosed()) {
			nestedBlock = null;
		}
		return ret;
	}

	private void maybeLineBreakParagraph() {
		if (!wasEmpty) {
			return;
		}
		builder.beginBlock(BlockType.PARAGRAPH, new Attributes());
		builder.endBlock();
		wasEmpty = false;
	}

	private Block clone(Block block) {
		Block newBlock = block.clone();
		newBlock.setState(getState());
		newBlock.setParser(getParser());
		return newBlock;
	}

	private void createNewBlock(String line, int offset) {
		for (Block block : markupLanguage.getBlocks()) {
			if (block.canStart(line, offset)) {
				nestedBlock = clone(block);
				break;
			}
		}
	}

	@Override
	protected int processLineContent(String line, int offset) {

		String text = line.substring(offset);

		// check start of block/item
		Matcher itemStartMatcher = itemStartPattern.matcher(text);

		if (itemStartMatcher.matches()) {
			int curIndent = itemStartMatcher.group(1).length();
			BlockType blockType = itemStartMatcher.group(2) != null ? BlockType.BULLETED_LIST : BlockType.NUMERIC_LIST;

			if (blockLineCount == 0) {
				// start list block
				builder.beginBlock(blockType, new Attributes());
				wsLevel = curIndent;
				contentLevel = itemStartMatcher.start(4);
			} else {
				blockLineCount++;
				if (nestedBlock != null) {
					ChildStatus cs = dispatchToChild(line, offset);
					if (!cs.closed) {
						return cs.offset;
					}
				} else {
					if (curIndent > wsLevel) {
						createNewBlock(line, offset);
						return processLineContent(line, offset);

					} else if (curIndent < wsLevel) {
						// Delegate to parent block?
						setClosed(true);
						return offset;
					} // else { just a normal line
				}
				builder.endBlock();
			}

			wasEmpty = false; // Ignore dummy line breaks
			builder.beginBlock(BlockType.LIST_ITEM, new Attributes());
			// extract content
			offset += itemStartMatcher.start(4);

		} else {
			if (nestedBlock != null) {
				ChildStatus cs = dispatchToChild(line, offset);
				if (!cs.closed) {
					return cs.offset;
				} // Otherwise, figure out why we're closed
			}

			if (text.trim().isEmpty()) {
				wasEmpty = true;
				// We ignore empty lines here, unless there's something else!
				return offset + text.length();

			} else {
				Matcher m = leadingSpaces.matcher(text);
				int newLevel = m.find() ? m.group(1).length() : 0;
				if (newLevel < contentLevel) {
					setClosed(true);
					return offset;
				} else {
					if (newLevel > contentLevel) {
						createNewBlock(line, offset);
						if (nestedBlock != null) {
							return nestedBlock.processLine(line, offset);
						}
					}
				}
			}
		}

		// Maybe it's time to insert a line break here?
		maybeLineBreakParagraph();
		markupLanguage.emitMarkupLine(getParser(), state, line, offset);
		blockLineCount++;
		return -1;
	}

	@Override
	public void setClosed(boolean closed) {
		if (closed && !isClosed()) {
			if (nestedBlock != null) {
				nestedBlock.setClosed(true);
				nestedBlock = null;
			}
			// end list item
			builder.endBlock();
			// end list block
			builder.endBlock();
		}
		super.setClosed(closed);
	}

}
