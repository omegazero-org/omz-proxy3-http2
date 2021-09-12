/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.hpack;

import java.io.IOException;
import java.util.Arrays;

import org.omegazero.common.util.ArrayUtil;

public class HPackHuffmanCoding {


	private static final int[] encodedLen = new int[256];
	private static final int[] encoded = new int[256]; // all values are below 32 bits
	private static final TreeNode root = new TreeNode();


	/**
	 * Decodes the given huffman-encoded <b>data</b> according to <i>RFC 7541, section 5.2</i>.
	 * 
	 * @param data The huffman-encoded data
	 * @return The decoded string data, or <code>null</code> if a decoding error occurred
	 * @see #encode(byte[])
	 */
	public static byte[] decode(byte[] data) {
		if(data.length == 0)
			return data;
		byte[] buf = new byte[data.length];
		int buflen = 0;
		TreeNode node = root;
		int trailingBits = 0;
		for(int i = 0; i < data.length; i++){
			for(int j = 7; j >= 0; j--){
				boolean bit = (data[i] & (1 << j)) != 0;
				node = bit ? node.right : node.left;
				if(node == null)
					return null;
				else if(node.value >= 0){
					if(node.value > 255)
						return null;
					if(buflen == buf.length)
						buf = Arrays.copyOf(buf, buf.length * 2);
					buf[buflen++] = (byte) node.value;
					node = root;
					trailingBits = 0;
				}else{
					if(bit && trailingBits >= 0)
						trailingBits++;
					else
						trailingBits = -1;
				}
			}
		}
		if(trailingBits < 0 || trailingBits > 7)
			return null;
		return Arrays.copyOf(buf, buflen);
	}

	/**
	 * Encodes the given string <b>data</b> according to <i>RFC 7541, section 5.2</i>.
	 * 
	 * @param data The string data
	 * @return The encoded data
	 * @see #decode(byte[])
	 */
	public static byte[] encode(byte[] data) {
		if(data.length == 0)
			return data;
		byte[] buf = new byte[data.length];
		int buflen = 0;
		int byteIndex = 7;
		for(int i = 0; i < data.length; i++){
			int bitsVal = encoded[data[i] & 0xff];
			int bitsNum = encodedLen[data[i] & 0xff];
			for(int j = 0; j < bitsNum; j++){
				if(byteIndex < 0){
					buflen++;
					if(buflen == buf.length)
						buf = Arrays.copyOf(buf, buf.length * 2);
					byteIndex = 7;
				}
				if((bitsVal & (1L << j)) != 0)
					buf[buflen] |= 1 << byteIndex;
				byteIndex--;
			}
		}
		for(int k = byteIndex; k >= 0; k--)
			buf[buflen] |= 1 << k; // MSBs of EOS
		buflen++;
		return Arrays.copyOf(buf, buflen);
	}


	private static class TreeNode {

		public final int value;
		public TreeNode left; // 0
		public TreeNode right; // 1

		public TreeNode() {
			this(-1);
		}

		public TreeNode(int value) {
			this.value = value;
		}
	}


	static{
		String[] lines;
		try{
			lines = new String(ArrayUtil.readInputStreamToByteArray(HPackHuffmanCoding.class.getResourceAsStream("hpack_huffman_code"))).split("\n");
		}catch(IOException e){
			throw new java.io.UncheckedIOException(e);
		}
		for(String s : lines){
			if(s.startsWith("#"))
				continue;
			int be = s.indexOf(' ');
			if(be < 0)
				continue;
			String bits = s.substring(0, be);
			int bitsNum = bits.length();
			int bitsVal = 0;
			int number = Integer.parseInt(s.substring(be + 1));
			TreeNode current = root;
			for(int i = 0; i < bitsNum; i++){
				char bit = bits.charAt(i);
				if(bit == '0'){
					if(i == bitsNum - 1){
						if(current.left != null)
							throw new RuntimeException("Could not add node for value " + number + " to tree because left node is not a leaf node");
						current.left = new TreeNode(number);
					}else{
						if(current.left == null)
							current.left = new TreeNode();
						current = current.left;
					}
				}else if(bit == '1'){
					if(i == bitsNum - 1){
						if(current.right != null)
							throw new RuntimeException("Could not add node for value " + number + " to tree right node is not a leaf node");
						current.right = new TreeNode(number);
					}else{
						if(current.right == null)
							current.right = new TreeNode();
						current = current.right;
					}
					bitsVal |= 1L << i;
				}else
					throw new RuntimeException("Unknown bit representation: " + bit);
				if(current.value >= 0)
					throw new RuntimeException("Cannot add node for value " + number + " because a leaf node was passed");
			}
			if(number < 256){
				encodedLen[number] = bitsNum;
				encoded[number] = bitsVal;
			}
		}
	}
}
