import unittest
from review32 import review,segments
class ReviewTests(unittest.TestCase):
    def test_inside(self): self.assertEqual(review([(0,50,30),(20,50,45),(40,50,60)],45,55,50)['classification'],'IN_ZONE')
    def test_boundary(self): self.assertEqual(review([(0,45,30),(20,45,45),(40,45,60)],45,55,50)['classification'],'UNCERTAIN')
    def test_outside(self): self.assertEqual(review([(0,70,30),(20,70,45),(40,70,60)],45,55,50)['classification'],'OUTSIDE')
    def test_gap(self):
        rows=[dict(x=50,y=40,width=100,height=100,image_timestamp_ns=t*1000000) for t in (0,20,100)]
        self.assertEqual([len(s) for s in segments(rows)],[2,1])
    def test_short(self): self.assertEqual(review([(0,50,30)],45,55,50)['classification'],'UNCERTAIN')
