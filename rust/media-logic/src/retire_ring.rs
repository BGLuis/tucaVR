//! Anel de posse para recursos que a GPU ainda pode estar lendo.
//!
//! O frame de video em uso e um `AImage` do `AImageReader`: soltar o anterior
//! devolve o buffer ao MediaCodec, que pode escrever o proximo quadro nele
//! enquanto ate `kMaxFramesInFlight` command buffers ainda o amostram (U-01,
//! docs/reports/OTIMIZACAO-RECURSOS-E-RENDERIZACAO.md). Em vez de soltar na
//! troca, o recurso substituido fica "aposentado" no anel e so e liberado
//! depois de `capacity` substituicoes seguintes — margem de `capacity` quadros
//! de video, sem depender de fence atravessando Rust/C++.

use std::collections::VecDeque;

pub struct RetireRing<T> {
    capacity: usize,
    retired: VecDeque<T>,
}

impl<T> RetireRing<T> {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity,
            retired: VecDeque::with_capacity(capacity + 1),
        }
    }

    /// Aposenta `item`; o mais antigo alem de `capacity` e liberado (drop).
    pub fn retire(&mut self, item: T) {
        self.retired.push_back(item);
        while self.retired.len() > self.capacity {
            self.retired.pop_front();
        }
    }

    /// Libera tudo (troca de sessao: o reader inteiro vai embora).
    pub fn clear(&mut self) {
        self.retired.clear();
    }

    pub fn len(&self) -> usize {
        self.retired.len()
    }

    pub fn is_empty(&self) -> bool {
        self.retired.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::rc::Rc;

    struct Tracked(Rc<Cell<u32>>);
    impl Drop for Tracked {
        fn drop(&mut self) {
            self.0.set(self.0.get() + 1);
        }
    }

    #[test]
    fn item_is_only_released_after_capacity_more_retirements() {
        let dropped = Rc::new(Cell::new(0));
        let mut ring = RetireRing::new(2);

        ring.retire(Tracked(dropped.clone()));
        ring.retire(Tracked(dropped.clone()));
        assert_eq!(
            dropped.get(),
            0,
            "com 2 frames em voo nenhum pode ser liberado ainda"
        );

        ring.retire(Tracked(dropped.clone()));
        assert_eq!(dropped.get(), 1, "o mais antigo sai quando o 3o entra");
        assert_eq!(ring.len(), 2);
    }

    #[test]
    fn clear_releases_everything() {
        let dropped = Rc::new(Cell::new(0));
        let mut ring = RetireRing::new(3);
        ring.retire(Tracked(dropped.clone()));
        ring.retire(Tracked(dropped.clone()));
        ring.clear();
        assert_eq!(dropped.get(), 2);
        assert!(ring.is_empty());
    }
}
