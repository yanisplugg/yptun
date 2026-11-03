use std::{
    cell::UnsafeCell,
    mem::MaybeUninit,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
};
use tokio::sync::Notify;

struct Ring<T> {
    slots: Box<[UnsafeCell<MaybeUninit<T>>]>,
    capacity: usize,
    write: AtomicUsize,
    read: AtomicUsize,
    wake_pending: AtomicBool,
    notify: Arc<Notify>,
}

unsafe impl<T: Send> Sync for Ring<T> {}

impl<T> Drop for Ring<T> {
    fn drop(&mut self) {
        let mut read = self.read.load(Ordering::Relaxed);
        let write = self.write.load(Ordering::Relaxed);
        while read != write {
            unsafe {
                (*self.slots[read].get()).assume_init_drop();
            }
            read = advance(read, self.capacity);
        }
    }
}

pub struct SpscSender<T> {
    ring: Arc<Ring<T>>,
    write: usize,
}

pub struct SpscReceiver<T> {
    ring: Arc<Ring<T>>,
    read: usize,
}

pub enum TrySendError<T> {
    Full(T),
}

impl<T> TrySendError<T> {
    #[cfg(test)]
    pub fn into_inner(self) -> T {
        match self {
            Self::Full(value) => value,
        }
    }
}

#[inline(always)]
fn advance(index: usize, capacity: usize) -> usize {
    let next = index + 1;
    if next == capacity { 0 } else { next }
}

pub fn channel<T>(capacity: usize) -> (SpscSender<T>, SpscReceiver<T>) {
    let capacity = capacity.max(1).saturating_add(1);
    let mut slots = Vec::with_capacity(capacity);
    slots.resize_with(capacity, || UnsafeCell::new(MaybeUninit::uninit()));
    let ring = Arc::new(Ring {
        slots: slots.into_boxed_slice(),
        capacity,
        write: AtomicUsize::new(0),
        read: AtomicUsize::new(0),
        wake_pending: AtomicBool::new(false),
        notify: Arc::new(Notify::new()),
    });
    (
        SpscSender {
            ring: ring.clone(),
            write: 0,
        },
        SpscReceiver { ring, read: 0 },
    )
}

impl<T> SpscSender<T> {
    #[inline(always)]
    pub fn try_send(&mut self, value: T) -> Result<(), TrySendError<T>> {
        let next = advance(self.write, self.ring.capacity);
        if next == self.ring.read.load(Ordering::Acquire) {
            return Err(TrySendError::Full(value));
        }
        unsafe {
            (*self.ring.slots[self.write].get()).write(value);
        }
        self.write = next;
        self.ring.write.store(next, Ordering::Release);
        if !self.ring.wake_pending.swap(true, Ordering::AcqRel) {
            self.ring.notify.notify_one();
        }
        Ok(())
    }
}

impl<T> SpscReceiver<T> {
    #[inline(always)]
    pub fn try_recv(&mut self) -> Option<T> {
        let write = self.ring.write.load(Ordering::Acquire);
        if self.read == write {
            return None;
        }
        let value = unsafe { (*self.ring.slots[self.read].get()).assume_init_read() };
        self.read = advance(self.read, self.ring.capacity);
        self.ring.read.store(self.read, Ordering::Release);
        Some(value)
    }

    #[inline(always)]
    pub fn notifier(&self) -> Arc<Notify> {
        self.ring.notify.clone()
    }

    #[inline(always)]
    pub fn rearm_notification(&self) -> bool {
        self.ring.wake_pending.swap(false, Ordering::AcqRel)
    }
}

#[cfg(test)]
mod tests {
    use super::channel;
    use std::{sync::mpsc, thread};

    #[test]
    fn preserves_fifo_and_capacity() {
        let (mut tx, mut rx) = channel(2);
        assert!(tx.try_send(10).is_ok());
        assert!(tx.try_send(20).is_ok());
        assert!(tx.try_send(30).is_err());
        assert_eq!(rx.try_recv(), Some(10));
        assert!(tx.try_send(30).is_ok());
        assert_eq!(rx.try_recv(), Some(20));
        assert_eq!(rx.try_recv(), Some(30));
        assert_eq!(rx.try_recv(), None);
    }

    #[test]
    fn transfers_across_threads_in_order() {
        const COUNT: usize = 100_000;
        let (mut tx, mut rx) = channel(257);
        let (done_tx, done_rx) = mpsc::channel();
        thread::scope(|scope| {
            scope.spawn(move || {
                for value in 0..COUNT {
                    let mut value = value;
                    loop {
                        match tx.try_send(value) {
                            Ok(()) => break,
                            Err(error) => {
                                value = error.into_inner();
                                std::hint::spin_loop();
                            }
                        }
                    }
                }
                done_tx.send(()).unwrap();
            });
            for expected in 0..COUNT {
                loop {
                    if let Some(value) = rx.try_recv() {
                        assert_eq!(value, expected);
                        break;
                    }
                    std::hint::spin_loop();
                }
            }
        });
        done_rx.recv().unwrap();
    }

    #[tokio::test]
    async fn notification_wakes_a_rearmed_empty_receiver() {
        let (mut tx, mut rx) = channel(1);
        let ready = rx.notifier().notified_owned();
        tokio::pin!(ready);
        rx.rearm_notification();
        assert!(tx.try_send(7u8).is_ok());
        tokio::time::timeout(std::time::Duration::from_secs(1), &mut ready)
            .await
            .unwrap();
        assert_eq!(rx.try_recv(), Some(7));
    }

    #[tokio::test]
    async fn rearm_race_keeps_the_already_queued_value_visible() {
        let (mut tx, mut rx) = channel(1);
        assert!(tx.try_send(9u8).is_ok());
        let ready = rx.notifier().notified_owned();
        tokio::pin!(ready);
        rx.rearm_notification();
        assert_eq!(rx.try_recv(), Some(9));
    }
}
