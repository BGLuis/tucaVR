//! Element text for quick-xml >= 0.38, which reports `&amp;`/`&#38;` as
//! `Event::GeneralRef`s between the `Event::Text` chunks of one text node.

use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesRef, BytesText};

pub(crate) fn push_text(out: &mut String, text: &BytesText) {
    out.push_str(&text.xml10_content());
}

/// An undeclared entity is kept as written instead of failing the whole document.
pub(crate) fn push_ref(out: &mut String, reference: &BytesRef) -> Result<(), String> {
    if let Some(ch) = reference.resolve_char_ref().map_err(|e| e.to_string())? {
        out.push(ch);
    } else if let Some(resolved) = resolve_predefined_entity(reference) {
        out.push_str(resolved);
    } else {
        out.push('&');
        out.push_str(reference);
        out.push(';');
    }
    Ok(())
}
