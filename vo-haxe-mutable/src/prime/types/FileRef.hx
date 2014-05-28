/*
 * Copyright (c) 2010, The PrimeVC Project Contributors
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE PRIMEVC PROJECT CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE PRIMVC PROJECT CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 *
 *
 * Authors:
 *  Ruben Weijers	<ruben @ prime.vc>
 */
package prime.types;
  using StringTools;

/**
 * FileRef class is meant for files that are hosted by online-touch. Use URI-class
 * for uri's that are hosted externally.
 *
 * TODO: Implement mostly the same functionality as the Scala version.
 */
class FileRef extends prime.types.URI
{
	/**
	 * prefix that will be added before every URI.toString
	 * By not adding the prefix to the URI itself we can make sure the data
	 * isn't saved with a prefix
	 */
	public static var prefix (default, set_prefix) : String;

	public function toURIString(cdnPostfix : String)
	{
		return (hasScheme(URIScheme.Scheme("cassandra"))
				? (prefix != null? prefix : "") + (host != null? host : "") + cdnPostfix
				: (scheme == null && prefix != null ? prefix + super.toString() : super.toString()) + (path.indexOf(".") == -1? cdnPostfix : ""));
	}

	public function toURI(?cdnPostfix : String) return new URI(toURIString(if (cdnPostfix == null) "" else cdnPostfix.toLowerCase()));
	
	override public function toString () return toURIString("");
	
	private static inline function set_prefix (v:String)
	{
		return prefix = (v != null && v != "" && v.charAt(v.length - 1) != "/") ? v + "/" : v;
	}
}
